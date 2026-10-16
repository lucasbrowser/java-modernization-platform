package com.github.lucasoliveira.platform;

import java.math.BigDecimal;
import java.util.UUID;
import com.github.lucasoliveira.platform.auth.dto.LoginRequest;
import com.github.lucasoliveira.platform.auth.dto.RegisterRequest;
import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.auth.service.AuthService;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.nullValue;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

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

    @Test
    void shouldMigrateLegacyUsersWithoutCreatingCustomerLinks() {
        String schema = "legacy_" + UUID.randomUUID().toString().replace("-", "");

        try {
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("3"))
                    .load()
                    .migrate();

            JdbcTemplate legacyJdbc = jdbcTemplateForSchema(schema);
            legacyJdbc.update(
                    "INSERT INTO " + schema + ".users (name, email, password, role) VALUES (?, ?, ?, ?)",
                    "Usuário legado",
                    "legacy.same@example.com",
                    "encoded-password",
                    "USER"
            );
            legacyJdbc.update(
                    "INSERT INTO " + schema + ".customers (name, email, document) VALUES (?, ?, ?)",
                    "Cliente legado",
                    "legacy.same@example.com",
                    "LEGACY-SAME-001"
            );

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            assertEquals(
                    1,
                    legacyJdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + schema + ".users WHERE email = ?",
                            Integer.class,
                            "legacy.same@example.com"
                    )
            );
            assertEquals(
                    1,
                    legacyJdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + schema + ".customers WHERE email = ?",
                            Integer.class,
                            "legacy.same@example.com"
                    )
            );
            assertEquals(
                    1,
                    legacyJdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + schema + ".users "
                                    + "WHERE email = ? AND customer_id IS NULL",
                            Integer.class,
                            "legacy.same@example.com"
                    )
            );
            assertEquals(
                    0,
                    legacyJdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + schema + ".user_customer_link_audit",
                            Integer.class
                    )
            );
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void shouldRejectUpdatesAndDeletesFromAppendOnlyAuditTable() {
        jdbcTemplate.update(
                "INSERT INTO users (name, email, password, role) VALUES (?, ?, ?, ?)",
                "Audit actor",
                "audit-actor-append-only@example.com",
                "encoded-password",
                "ADMIN"
        );
        jdbcTemplate.update(
                "INSERT INTO users (name, email, password, role) VALUES (?, ?, ?, ?)",
                "Audit target",
                "audit-target-append-only@example.com",
                "encoded-password",
                "USER"
        );
        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO user_customer_link_audit (
                    id, actor_user_id, target_user_id, action, justification
                ) VALUES (
                    ?,
                    (SELECT id FROM users WHERE email = ?),
                    (SELECT id FROM users WHERE email = ?),
                    ?,
                    ?
                )
                """,
                auditId,
                "audit-actor-append-only@example.com",
                "audit-target-append-only@example.com",
                "CREATE",
                "Append-only verification"
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "UPDATE user_customer_link_audit SET justification = ? WHERE id = ?",
                        "modified",
                        auditId
                )
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "DELETE FROM user_customer_link_audit WHERE id = ?",
                        auditId
                )
        );
    }

    @Test
    void shouldResolvePersistedUserAndCustomerFromJwt() throws Exception {
        Customer customer = new Customer();
        customer.setName("Cliente do JWT");
        customer.setEmail("jwt.customer@example.com");
        customer.setDocument("JWT-CUSTOMER-001");
        Customer savedCustomer = customerRepository.saveAndFlush(customer);

        User user = new User();
        user.setName("Usuário JWT associado");
        user.setEmail("jwt.linked.user@example.com");
        user.setPassword("encoded-password");
        user.setRole(Role.USER);
        user.setCustomerId(savedCustomer.getId());
        User savedUser = userRepository.saveAndFlush(user);

        String token = jwtService.generateToken(savedUser.getEmail(), "USER");

        mockMvc.perform(get("/test/authenticated-user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(savedUser.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(savedCustomer.getId().toString()))
                .andExpect(jsonPath("$.hasCustomerAssociation").value(true))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"));
    }

    @Test
    void shouldKeepRegisteredUserUnlinkedAndResolveItAfterLogin() throws Exception {
        String email = "jwt.unlinked.user@example.com";
        Customer customer = new Customer();
        customer.setName("Mesmo e-mail do usuário");
        customer.setEmail(email);
        customer.setDocument("JWT-UNLINKED-001");
        customerRepository.saveAndFlush(customer);

        authService.register(new RegisterRequest(
                "Usuário sem vínculo",
                email,
                "password123"
        ));
        String token = authService.login(new LoginRequest(email, "password123"))
                .accessToken();
        User persistedUser = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow();

        assertNull(persistedUser.getCustomerId());
        mockMvc.perform(get("/test/authenticated-user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(persistedUser.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(nullValue()))
                .andExpect(jsonPath("$.hasCustomerAssociation").value(false))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"));
    }

    @Test
    void shouldRequireValidJwtBeforeResolvingCurrentUser() throws Exception {
        mockMvc.perform(get("/test/authenticated-user"))
                .andExpect(status().isUnauthorized());
    }

    private JdbcTemplate jdbcTemplateForSchema(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl() + "?currentSchema=" + schema,
                postgres.getUsername(),
                postgres.getPassword()
        );
        return new JdbcTemplate(dataSource);
    }
}
