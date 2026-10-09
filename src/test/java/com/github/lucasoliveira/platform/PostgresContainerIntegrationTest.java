package com.github.lucasoliveira.platform;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PostgresContainerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldConnectToPostgresContainer() {
        String databaseName = jdbcTemplate.queryForObject(
                "SELECT current_database()",
                String.class
        );

        assertNotNull(databaseName);
        assertEquals("test", databaseName);
    }

    @Test
    void shouldExecuteDatabaseQuery() {
        Integer result = jdbcTemplate.queryForObject(
                "SELECT 1",
                Integer.class
        );

        assertEquals(1, result);
    }

    @Test
    void shouldRunFlywayMigrations() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'users'
                """,
                Integer.class
        );

        assertEquals(1, tableCount);
    }

    @Test
    void shouldPersistAndFindIdempotencyKey() {
        jdbcTemplate.update(
                """
                INSERT INTO customers (
                    name,
                    email,
                    document
                )
                VALUES (?, ?, ?)
                """,
                "Cliente Idempotência",
                "idempotency@test.com",
                "DOC-IDEMPOTENCY-001"
        );

        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    customer_id,
                    status,
                    total_amount,
                    idempotency_key
                )
                VALUES (
                    (SELECT id FROM customers WHERE email = ?),
                    ?,
                    ?,
                    ?
                )
                """,
                "idempotency@test.com",
                "CREATED",
                new BigDecimal("100.00"),
                "integration-key-001"
        );

        String idempotencyKey = jdbcTemplate.queryForObject(
                """
                SELECT idempotency_key
                FROM orders
                WHERE idempotency_key = ?
                """,
                String.class,
                "integration-key-001"
        );

        assertEquals("integration-key-001", idempotencyKey);
    }

    @Test
    void shouldRejectDuplicateIdempotencyKey() {
        jdbcTemplate.update(
                """
                INSERT INTO customers (
                    name,
                    email,
                    document
                )
                VALUES (?, ?, ?)
                """,
                "Cliente Idempotência 2",
                "idempotency2@test.com",
                "DOC-IDEMPOTENCY-002"
        );

        String customerId = jdbcTemplate.queryForObject(
                """
                SELECT id::text
                FROM customers
                WHERE email = ?
                """,
                String.class,
                "idempotency2@test.com"
        );

        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    customer_id,
                    status,
                    total_amount,
                    idempotency_key
                )
                VALUES (?::uuid, ?, ?, ?)
                """,
                customerId,
                "CREATED",
                new BigDecimal("200.00"),
                "integration-key-002"
        );

        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO orders (
                            customer_id,
                            status,
                            total_amount,
                            idempotency_key
                        )
                        VALUES (?::uuid, ?, ?, ?)
                        """,
                        customerId,
                        "CREATED",
                        new BigDecimal("300.00"),
                        "integration-key-002"
                )
        );

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM orders
                WHERE idempotency_key = ?
                """,
                Integer.class,
                "integration-key-002"
        );

        assertEquals(1, count);
    }

    @Test
    void shouldRunProductVersionMigration() {
        Integer columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                AND table_name = 'products'
                AND column_name = 'version'
                """,
                Integer.class
        );

        assertEquals(1, columnCount);
    }

    @Test
    void shouldInitializeProductVersionWithZero() {
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    sku,
                    name,
                    price,
                    stock,
                    active
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                "VERSION-001",
                "Produto Versionado",
                new BigDecimal("100.00"),
                10,
                true
        );

        Long version = jdbcTemplate.queryForObject(
                """
                SELECT version
                FROM products
                WHERE sku = ?
                """,
                Long.class,
                "VERSION-001"
        );

        assertEquals(0L, version);
    }
}