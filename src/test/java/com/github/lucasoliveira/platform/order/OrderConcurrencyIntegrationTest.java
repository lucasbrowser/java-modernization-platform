package com.github.lucasoliveira.platform.order;

import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import com.github.lucasoliveira.platform.order.dto.OrderItemRequest;
import com.github.lucasoliveira.platform.order.dto.OrderRequest;
import com.github.lucasoliveira.platform.order.entity.Order;
import com.github.lucasoliveira.platform.order.repository.OrderRepository;
import com.github.lucasoliveira.platform.order.service.OrderService;
import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleObjectStateException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
public class OrderConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
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

    private final ExecutorService executor =
            Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldPreventOversellingWhenOrdersAreCreatedConcurrently()
            throws Exception {

        String suffix = UUID.randomUUID().toString();

        Customer customer = new Customer();
        customer.setName("Cliente Concorrencia");
        customer.setEmail("concurrency-" + suffix + "@example.com");
        customer.setDocument(suffix.replace("-", "").substring(0, 30));
        customer.setPhone("65999999999");

        customer = customerRepository.saveAndFlush(customer);

        Product product = new Product();
        product.setSku("CONC-" + suffix);
        product.setName("Produto Concorrente");
        product.setPrice(new BigDecimal("100.00"));
        product.setStock(10);
        product.setActive(true);

        product = productRepository.saveAndFlush(product);

        UUID customerId = customer.getId();
        UUID productId = product.getId();

        OrderRequest request = new OrderRequest(
                customerId,
                List.of(new OrderItemRequest(productId, 7))
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<Boolean> first = executor.submit(
                () -> createOrderConcurrently(
                        request,
                        "concurrency-key-1-" + suffix,
                        ready,
                        start
                )
        );

        Future<Boolean> second = executor.submit(
                () -> createOrderConcurrently(
                        request,
                        "concurrency-key-2-" + suffix,
                        ready,
                        start
                )
        );

        assertTrue(
                ready.await(10, TimeUnit.SECONDS),
                "Both tasks should be ready"
        );

        start.countDown();

        boolean firstSucceeded = first.get(30, TimeUnit.SECONDS);
        boolean secondSucceeded = second.get(30, TimeUnit.SECONDS);

        int successfulOrders =
                (firstSucceeded ? 1 : 0)
                        + (secondSucceeded ? 1 : 0);

        assertEquals(
                1,
                successfulOrders,
                "Exactly one concurrent order should succeed"
        );

        Product updatedProduct =
                productRepository.findById(productId).orElseThrow();

        assertEquals(
                3,
                updatedProduct.getStock(),
                "The remaining stock should be 3"
        );

        long persistedOrders = orderRepository.countByProductId(productId);

        assertEquals(
                1,
                persistedOrders,
                "Exactly one order for this product should be persisted"
        );

        assertNotNull(updatedProduct.getVersion());
        assertTrue(updatedProduct.getVersion() >= 1);
    }

    private boolean createOrderConcurrently(
            OrderRequest request,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {

        ready.countDown();

        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new TimeoutException(
                    "Timed out waiting to start order creation"
            );
        }

        try {
            new TransactionTemplate(transactionManager).execute(status ->
                    orderService.create(request, idempotencyKey)
            );

            return true;

        } catch (RuntimeException ex) {
            if (isExpectedConcurrencyOrStockFailure(ex)) {
                return false;
            }

            throw ex;
        }
    }

    private boolean isExpectedConcurrencyOrStockFailure(Throwable error) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof OptimisticLockingFailureException
                    || current instanceof OptimisticLockException
                    || current instanceof StaleObjectStateException) {
                return true;
            }

            if (current instanceof IllegalArgumentException
                    && current.getMessage() != null
                    && current.getMessage().contains("Insufficient stock")) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
