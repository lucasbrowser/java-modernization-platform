package com.github.lucasoliveira.platform.auth;

import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.auth.service.UserCustomerLinkService;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class UserCustomerLinkPostgresConsistencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("provision-test-roles.sql")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @Autowired UserCustomerLinkService service;
    @Autowired UserRepository userRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void auditInsertFailureRollsBackTheCustomerLinkInPostgres() {
        Fixture fixture = fixture("audit-failure", true);
        String functionName = "fail_link_audit_" + UUID.randomUUID().toString().replace("-", "");
        String triggerName = "fail_link_audit_" + UUID.randomUUID().toString().replace("-", "");
        createAuditFailureTrigger(functionName, triggerName, fixture.target().getId());

        try {
            assertThrows(RuntimeException.class, () -> inTransactionAs(fixture.actor(), () ->
                    service.assignCustomer(
                            fixture.target().getId(), fixture.secondCustomer().getId(), "Rollback proof")));
        } finally {
            dropAuditFailureTrigger(functionName, triggerName);
        }

        StateAfterTransaction state = newTransaction().execute(status -> new StateAfterTransaction(
                customerId(fixture.target().getId()), auditCount(fixture.target().getId())));
        assertNotNull(state);
        assertEquals(fixture.firstCustomer().getId(), state.customerId());
        assertEquals(0L, state.auditCount());
    }

    @Test
    void concurrentAssignmentsWaitOnPostgresRowLockAndProduceCompatibleAuditSnapshots() throws Exception {
        Fixture fixture = fixture("concurrent", true);
        String applicationName = "link-lock-" + UUID.randomUUID().toString().replace("-", "");
        String firstApplicationName = applicationName + "_a";
        String secondApplicationName = applicationName + "_b";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch oneOperationApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        Future<?> first = executor.submit(() -> concurrentAssignment(
                fixture, firstApplicationName, fixture.secondCustomer().getId(), "Concurrent first", ready, start,
                oneOperationApplied, allowCommit));
        Future<?> second = executor.submit(() -> concurrentAssignment(
                fixture, secondApplicationName, fixture.thirdCustomer().getId(), "Concurrent second", ready, start,
                oneOperationApplied, allowCommit));

        LockWait wait;
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Both independent transactions should be ready");
            start.countDown();
            wait = awaitPostgresLockWait(List.of(firstApplicationName, secondApplicationName), 15, TimeUnit.SECONDS);
            assertNotNull(wait, "PostgreSQL should report the second backend waiting on a lock");
            assertTrue(wait.blockingPidCount() > 0, "PostgreSQL should identify the lock-holding backend");
            assertTrue(oneOperationApplied.await(10, TimeUnit.SECONDS), "Lock holder should finish its update and wait to commit");
        } finally {
            start.countDown();
            allowCommit.countDown();
        }

        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        StateAfterTransaction state = newTransaction().execute(status -> new StateAfterTransaction(
                customerId(fixture.target().getId()), auditCount(fixture.target().getId())));
        assertNotNull(state);

        UUID customerHeldByBlockingTransaction = wait.blockingApplicationName().equals(firstApplicationName)
                ? fixture.secondCustomer().getId() : fixture.thirdCustomer().getId();
        UUID customerRequestedByWaitingTransaction = wait.waitingApplicationName().equals(firstApplicationName)
                ? fixture.secondCustomer().getId() : fixture.thirdCustomer().getId();
        assertEquals(customerRequestedByWaitingTransaction, state.customerId());

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT previous_customer_id, new_customer_id, action, justification " +
                        "FROM user_customer_link_audit WHERE target_user_id = ? ORDER BY occurred_at, id",
                fixture.target().getId());
        assertEquals(2, events.size());
        assertEquals(fixture.firstCustomer().getId(), events.get(0).get("previous_customer_id"));
        assertEquals(customerHeldByBlockingTransaction, events.get(0).get("new_customer_id"));
        assertEquals("CHANGE", events.get(0).get("action"));
        assertEquals(customerHeldByBlockingTransaction, events.get(1).get("previous_customer_id"));
        assertEquals(customerRequestedByWaitingTransaction, events.get(1).get("new_customer_id"));
        assertEquals("CHANGE", events.get(1).get("action"));
        assertEquals(2L, state.auditCount());
    }

    @Test
    void repeatedAssignmentAndRemovalWithoutLinkDoNotInsertAuditEvents() {
        Fixture linked = fixture("repeat-assignment", true);
        inTransactionAs(linked.actor(), () -> service.assignCustomer(
                linked.target().getId(), linked.firstCustomer().getId(), "Repeated assignment"));
        assertEquals(linked.firstCustomer().getId(), customerId(linked.target().getId()));
        assertEquals(0L, auditCount(linked.target().getId()));

        Fixture unlinked = fixture("repeat-removal", false);
        inTransactionAs(unlinked.actor(), () -> service.removeCustomer(unlinked.target().getId(), "Repeated removal"));
        assertEquals(null, customerId(unlinked.target().getId()));
        assertEquals(0L, auditCount(unlinked.target().getId()));
    }

    private void createAuditFailureTrigger(String functionName, String triggerName, UUID targetId) {
        jdbcTemplate.execute("CREATE FUNCTION " + functionName + "() RETURNS trigger LANGUAGE plpgsql AS $$ " +
                "BEGIN RAISE EXCEPTION 'forced audit insert failure for T-04'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER " + triggerName + " BEFORE INSERT ON user_customer_link_audit " +
                "FOR EACH ROW WHEN (NEW.target_user_id = '" + targetId + "') EXECUTE FUNCTION " + functionName + "()");
    }

    private void dropAuditFailureTrigger(String functionName, String triggerName) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON user_customer_link_audit");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
    }

    private LockWait awaitPostgresLockWait(List<String> applicationNames, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            List<LockWait> waits = jdbcTemplate.query(
                    "SELECT waiting.application_name AS waiting_app, blocker.application_name AS blocking_app, " +
                            "waiting.wait_event_type, waiting.wait_event, cardinality(pg_blocking_pids(waiting.pid)) AS blocking_count " +
                            "FROM pg_stat_activity waiting " +
                            "JOIN LATERAL unnest(pg_blocking_pids(waiting.pid)) AS blocker_pid(pid) ON TRUE " +
                            "JOIN pg_stat_activity blocker ON blocker.pid = blocker_pid.pid " +
                            "WHERE waiting.application_name IN (?, ?) AND waiting.state = 'active'",
                    (rs, rowNum) -> new LockWait(rs.getString("waiting_app"), rs.getString("blocking_app"),
                            rs.getString("wait_event_type"), rs.getString("wait_event"), rs.getInt("blocking_count")),
                    applicationNames.get(0), applicationNames.get(1));
            if (!waits.isEmpty() && "Lock".equals(waits.get(0).waitEventType())) {
                return waits.get(0);
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("PostgreSQL did not report the expected lock wait for " + applicationNames);
    }

    private void concurrentAssignment(
            Fixture fixture,
            String applicationName,
            UUID destinationCustomerId,
            String justification,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch oneOperationApplied,
            CountDownLatch allowCommit
    ) {
        inTransactionAs(fixture.actor(), () -> {
            jdbcTemplate.queryForObject("SELECT set_config('application_name', ?, true)", String.class, applicationName);
            ready.countDown();
            await(start);
            service.assignCustomer(fixture.target().getId(), destinationCustomerId, justification);
            oneOperationApplied.countDown();
            await(allowCommit);
            return null;
        });
    }

    private <T> T inTransactionAs(User actor, java.util.concurrent.Callable<T> operation) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                actor.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            return newTransaction().execute(status -> {
                try {
                    return operation.call();
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new TimeoutException("Timed out waiting for the concurrent test coordination latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        } catch (TimeoutException exception) {
            throw new RuntimeException(exception);
        }
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private Fixture fixture(String label, boolean initiallyLinked) {
        String suffix = label + "-" + UUID.randomUUID();
        Customer first = saveCustomer(suffix + "-first");
        Customer second = saveCustomer(suffix + "-second");
        Customer third = saveCustomer(suffix + "-third");
        User actor = saveUser(suffix + "-admin", Role.ADMIN, null);
        User target = saveUser(suffix + "-target", Role.USER, initiallyLinked ? first.getId() : null);
        return new Fixture(actor, target, first, second, third);
    }

    private Customer saveCustomer(String suffix) {
        Customer customer = new Customer();
        customer.setName("Customer " + suffix);
        customer.setEmail(suffix + "@example.com");
        customer.setDocument("DOC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        return customerRepository.saveAndFlush(customer);
    }

    private User saveUser(String suffix, Role role, UUID customerId) {
        User user = new User();
        user.setName("User " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPassword("encoded-password");
        user.setRole(role);
        user.setCustomerId(customerId);
        return userRepository.saveAndFlush(user);
    }

    private UUID customerId(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT customer_id FROM users WHERE id = ?", UUID.class, userId);
    }

    private long auditCount(UUID targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_customer_link_audit WHERE target_user_id = ?", Long.class, targetId);
    }

    private record Fixture(User actor, User target, Customer firstCustomer, Customer secondCustomer, Customer thirdCustomer) {
    }

    private record StateAfterTransaction(UUID customerId, long auditCount) {
    }

    private record LockWait(
            String waitingApplicationName,
            String blockingApplicationName,
            String waitEventType,
            String waitEvent,
            int blockingPidCount
    ) {
    }
}
