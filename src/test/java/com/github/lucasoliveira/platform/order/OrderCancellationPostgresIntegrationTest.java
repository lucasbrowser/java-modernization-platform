package com.github.lucasoliveira.platform.order;

import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import com.github.lucasoliveira.platform.order.dto.OrderItemRequest;
import com.github.lucasoliveira.platform.order.dto.OrderRequest;
import com.github.lucasoliveira.platform.order.entity.OrderStatus;
import com.github.lucasoliveira.platform.order.repository.OrderRepository;
import com.github.lucasoliveira.platform.order.service.OrderService;
import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class OrderCancellationPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private OrderService orderService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutDownExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        SecurityContextHolder.clearContext();
    }

    @Test
    void concurrentCancellationUsesPostgresOrderLockAndRestoresStockOnce() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Customer customer = saveCustomer(suffix);
        Product product = saveProduct("CC-" + suffix.substring(0, 12), 10);
        UUID orderId = createOrder(customer.getId(), product.getId(), 2, "cancel-concurrent-" + suffix);
        UUID productId = product.getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<OrderStatus> first = executor.submit(() -> cancelConcurrently(orderId, ready, start));
        Future<OrderStatus> second = executor.submit(() -> cancelConcurrently(orderId, ready, start));

        assertTrue(ready.await(10, TimeUnit.SECONDS), "Both cancellation transactions should be ready");

        try (Connection lockConnection = dataSource.getConnection()) {
            lockConnection.setAutoCommit(false);
            try (PreparedStatement statement = lockConnection.prepareStatement(
                    "SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, orderId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next(), "The order row should exist before acquiring its lock");
                }
            }

            start.countDown();
            awaitPostgresLockWaits(2);
            lockConnection.commit();
        }

        assertEquals(OrderStatus.CANCELLED, first.get(30, TimeUnit.SECONDS));
        assertEquals(OrderStatus.CANCELLED, second.get(30, TimeUnit.SECONDS));
        assertEquals(OrderStatus.CANCELLED, orderRepository.findById(orderId).orElseThrow().getStatus());
        assertEquals(10, productRepository.findById(productId).orElseThrow().getStock());

        runAsAdmin(() -> inTransaction(() -> orderService.cancel(orderId)));
        assertEquals(10, productRepository.findById(productId).orElseThrow().getStock(),
                "A later retry must not restore stock again");
    }

    @Test
    void persistenceFailureDuringStockRestoreRollsBackOrderAndEveryProduct() {
        String suffix = UUID.randomUUID().toString();
        Customer customer = saveCustomer(suffix);
        Product firstProduct = saveProduct("CRA-" + suffix.substring(0, 12), 10);
        Product failingProduct = saveProduct("CRB-" + suffix.substring(0, 12), 20);
        UUID firstProductId = firstProduct.getId();
        UUID failingProductId = failingProduct.getId();
        UUID orderId = createOrder(customer.getId(), firstProductId, 2,
                failingProductId, 3, "cancel-rollback-" + suffix);

        assertEquals(8, productRepository.findById(firstProductId).orElseThrow().getStock());
        assertEquals(17, productRepository.findById(failingProductId).orElseThrow().getStock());
        long firstVersion = productRepository.findById(firstProductId).orElseThrow().getVersion();
        long failingVersion = productRepository.findById(failingProductId).orElseThrow().getVersion();

        String functionName = "fail_cancel_stock_" + suffix.replace("-", "");
        String triggerName = "fail_cancel_stock_trigger_" + suffix.replace("-", "");
        createFailingStockTrigger(functionName, triggerName);
        try {
            RuntimeException failure = runAsAdmin(() -> assertThrows(RuntimeException.class, () -> inTransaction(
                    () -> orderService.cancel(orderId))));
            assertTrue(containsMessage(failure, "forced persistence failure for cancellation test"),
                    "The configured PostgreSQL trigger should cause the persistence failure");
        } finally {
            dropFailingStockTrigger(functionName, triggerName);
        }

        assertEquals(OrderStatus.CREATED, orderRepository.findById(orderId).orElseThrow().getStatus());
        assertEquals(8, productRepository.findById(firstProductId).orElseThrow().getStock());
        assertEquals(17, productRepository.findById(failingProductId).orElseThrow().getStock());
        assertEquals(firstVersion, productRepository.findById(firstProductId).orElseThrow().getVersion());
        assertEquals(failingVersion, productRepository.findById(failingProductId).orElseThrow().getVersion());
    }

    private OrderStatus cancelConcurrently(UUID orderId, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new TimeoutException("Timed out waiting to start cancellation");
        }
        SecurityContextHolder.getContext().setAuthentication(adminAuthentication());
        try {
            return inTransaction(() -> orderService.cancel(orderId).status());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void awaitPostgresLockWaits(int expectedWaiters) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String sql = """
                SELECT COUNT(*)
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND wait_event_type = 'Lock'
                  AND query ILIKE '%from orders%'
                  AND pid <> pg_backend_pid()
                """;
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbcTemplate.queryForObject(sql, Integer.class);
            if (waiters != null && waiters >= expectedWaiters) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("PostgreSQL did not report both cancellation queries waiting on the order lock");
    }

    private Customer saveCustomer(String suffix) {
        Customer customer = new Customer();
        customer.setName("Cancellation customer " + suffix);
        customer.setEmail("cancel-" + suffix + "@example.com");
        customer.setDocument("DOC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        return customerRepository.saveAndFlush(customer);
    }

    private Product saveProduct(String sku, int stock) {
        Product product = new Product();
        product.setSku(sku);
        product.setName(sku);
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(stock);
        product.setActive(true);
        return productRepository.saveAndFlush(product);
    }

    private UUID createOrder(UUID customerId, UUID productId, int quantity, String idempotencyKey) {
        return createOrder(customerId, List.of(new OrderItemRequest(productId, quantity)), idempotencyKey);
    }

    private UUID createOrder(UUID customerId, UUID firstProductId, int firstQuantity,
                             UUID secondProductId, int secondQuantity, String idempotencyKey) {
        return createOrder(customerId, List.of(
                new OrderItemRequest(firstProductId, firstQuantity),
                new OrderItemRequest(secondProductId, secondQuantity)), idempotencyKey);
    }

    private UUID createOrder(UUID customerId, List<OrderItemRequest> items, String idempotencyKey) {
        return runAsAdmin(() -> inTransaction(() -> orderService.create(
                new OrderRequest(customerId, items), idempotencyKey).id()));
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private <T> T runAsAdmin(java.util.concurrent.Callable<T> work) {
        SecurityContextHolder.getContext().setAuthentication(adminAuthentication());
        try {
            return work.call();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UsernamePasswordAuthenticationToken adminAuthentication() {
        return new UsernamePasswordAuthenticationToken("cancellation-admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private void createFailingStockTrigger(String functionName, String triggerName) {
        String createFunction = """
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                DECLARE update_count integer;
                BEGIN
                    update_count := COALESCE(NULLIF(current_setting('java_modernization.cancel_stock_updates', true), '')::integer, 0) + 1;
                    PERFORM set_config('java_modernization.cancel_stock_updates', update_count::text, true);
                    IF update_count = 2 THEN
                        RAISE EXCEPTION 'forced persistence failure for cancellation test';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(functionName);
        jdbcTemplate.execute(createFunction);
        jdbcTemplate.execute("CREATE TRIGGER " + triggerName
                + " BEFORE UPDATE OF stock ON products FOR EACH ROW EXECUTE FUNCTION " + functionName + "()");
    }

    private void dropFailingStockTrigger(String functionName, String triggerName) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON products");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
    }

    private boolean containsMessage(Throwable error, String expectedMessage) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expectedMessage)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
