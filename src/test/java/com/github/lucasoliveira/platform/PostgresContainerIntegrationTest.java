package com.github.lucasoliveira.platform;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.github.lucasoliveira.platform.auth.dto.LoginRequest;
import com.github.lucasoliveira.platform.auth.dto.RegisterRequest;
import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.auth.service.AuthService;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.order.entity.OrderStatus;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;
import com.github.lucasoliveira.platform.order.entity.Order;
import com.github.lucasoliveira.platform.order.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import org.springframework.http.MediaType;
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
                    .withInitScript("provision-test-roles.sql")
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
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ObjectMapper objectMapper;

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

    @Test
    void shouldEnforceOrderOwnershipAndIdempotencyThroughRealJwtAndPostgres() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Customer owner = saveCustomer("owner-" + suffix);
        Customer other = saveCustomer("other-" + suffix);
        User linkedUser = saveUser("linked-" + suffix, Role.USER, owner.getId());
        User unlinkedUser = saveUser("unlinked-" + suffix, Role.USER, null);
        User admin = saveUser("admin-" + suffix, Role.ADMIN, null);

        Product product = new Product();
        product.setSku("AUTH-" + suffix);
        product.setName("Authorization test product");
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(5);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        String userToken = jwtService.generateToken(linkedUser.getEmail(), "USER");
        String unlinkedToken = jwtService.generateToken(unlinkedUser.getEmail(), "USER");
        String adminToken = jwtService.generateToken(admin.getEmail(), "ADMIN");

        String adminKey = "admin-" + suffix;
        String adminBody = orderBody(other.getId(), product.getId());
        String adminOrderJson = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", adminKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID otherOrderId = UUID.fromString(objectMapper.readTree(adminOrderJson).get("id").asText());

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", "admin-owner-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(owner.getId(), product.getId())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/orders/{id}", otherOrderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/customer/{customerId}", other.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/customer/{customerId}", owner.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/orders/customer/{customerId}", other.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        String ownerKey = "owner-" + suffix;
        String ownerBody = orderBody(owner.getId(), product.getId());
        String ownerOrderJson = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", ownerKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ownerBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID ownerOrderId = UUID.fromString(objectMapper.readTree(ownerOrderJson).get("id").asText());

        mockMvc.perform(get("/orders/{id}", ownerOrderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/orders/customer/{customerId}", owner.getId())
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", "mismatch-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(other.getId(), product.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", adminKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ownerBody))
                .andExpect(status().isNotFound());

        String replayJson = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + userToken)
                        .header("Idempotency-Key", ownerKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ownerBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(ownerOrderId.toString(), objectMapper.readTree(replayJson).get("id").asText());

        mockMvc.perform(get("/orders/customer/{customerId}", owner.getId())
                        .header("Authorization", "Bearer " + unlinkedToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/orders/{id}", ownerOrderId)
                        .header("Authorization", "Bearer " + unlinkedToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + unlinkedToken)
                        .header("Idempotency-Key", "unlinked-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ownerBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/orders/{id}", ownerOrderId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/orders")
                        .header("Idempotency-Key", "anonymous-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ownerBody))
                .andExpect(status().isUnauthorized());

        assertEquals(2, productRepository.findById(product.getId()).orElseThrow().getStock());
        assertEquals(2, orderRepository.findByCustomerId(owner.getId()).size());
        assertEquals(1, orderRepository.findByCustomerId(other.getId()).size());
    }

    @Test
    void shouldApplyOrderAuthorizationAfterCustomerLinkAssignmentChangeAndRemoval() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Customer customerA = saveCustomer("link-lifecycle-a-" + suffix);
        Customer customerB = saveCustomer("link-lifecycle-b-" + suffix);
        User admin = saveUser("link-lifecycle-admin-" + suffix, Role.ADMIN, null);
        User target = saveUser("link-lifecycle-user-" + suffix, Role.USER, null);

        Product product = new Product();
        product.setSku("LL-" + suffix);
        product.setName("Link lifecycle product");
        product.setPrice(new BigDecimal("12.50"));
        product.setStock(10);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        String adminToken = jwtService.generateToken(admin.getEmail(), "ADMIN");
        String targetToken = jwtService.generateToken(target.getEmail(), "USER");
        UUID orderAId = createOrderViaJwt(
                customerA.getId(), product.getId(), adminToken, "link-lifecycle-a-" + suffix);
        UUID orderBId = createOrderViaJwt(
                customerB.getId(), product.getId(), adminToken, "link-lifecycle-b-" + suffix);

        mockMvc.perform(put("/admin/users/{userId}/customer-link", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerLinkAssignmentBody(customerA.getId(), "Initial assignment")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(target.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(customerA.getId().toString()))
                .andExpect(jsonPath("$.changed").value(true));
        assertEquals(customerA.getId(), persistedCustomerId(target.getId()));
        assertCustomerLinkAudit(target.getId(), admin.getId(), List.of(
                new ExpectedLinkAudit("CREATE", null, customerA.getId())));

        mockMvc.perform(get("/orders/{id}", orderAId).header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderAId.toString()))
                .andExpect(jsonPath("$.customerId").value(customerA.getId().toString()));
        mockMvc.perform(get("/orders/{id}", orderBId).header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/customer/{customerId}", customerA.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(orderAId.toString()));
        mockMvc.perform(get("/orders/customer/{customerId}", customerB.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/admin/users/{userId}/customer-link", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerLinkAssignmentBody(customerB.getId(), "Change to B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(target.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(customerB.getId().toString()))
                .andExpect(jsonPath("$.changed").value(true));
        assertEquals(customerB.getId(), persistedCustomerId(target.getId()));
        assertCustomerLinkAudit(target.getId(), admin.getId(), List.of(
                new ExpectedLinkAudit("CREATE", null, customerA.getId()),
                new ExpectedLinkAudit("CHANGE", customerA.getId(), customerB.getId())));

        mockMvc.perform(get("/orders/{id}", orderAId).header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/{id}", orderBId).header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderBId.toString()))
                .andExpect(jsonPath("$.customerId").value(customerB.getId().toString()));
        mockMvc.perform(get("/orders/customer/{customerId}", customerA.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/orders/customer/{customerId}", customerB.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(orderBId.toString()));

        mockMvc.perform(post("/admin/users/{userId}/customer-link/removal", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("justification", "Remove customer link"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(target.getId().toString()))
                .andExpect(jsonPath("$.customerId").value(nullValue()))
                .andExpect(jsonPath("$.changed").value(true));
        assertNull(persistedCustomerId(target.getId()));
        assertCustomerLinkAudit(target.getId(), admin.getId(), List.of(
                new ExpectedLinkAudit("CREATE", null, customerA.getId()),
                new ExpectedLinkAudit("CHANGE", customerA.getId(), customerB.getId()),
                new ExpectedLinkAudit("REMOVE", customerB.getId(), null)));

        mockMvc.perform(get("/orders/{id}", orderAId).header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/orders/{id}", orderBId).header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/orders/customer/{customerId}", customerA.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/orders/customer/{customerId}", customerB.getId())
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden());

        assertEquals(OrderStatus.CREATED, orderRepository.findById(orderAId).orElseThrow().getStatus());
        assertEquals(OrderStatus.CREATED, orderRepository.findById(orderBId).orElseThrow().getStatus());
        assertEquals(8, productRepository.findById(product.getId()).orElseThrow().getStock());
    }

    private String customerLinkAssignmentBody(UUID customerId, String justification) throws Exception {
        return objectMapper.writeValueAsString(Map.of("customerId", customerId, "justification", justification));
    }

    private UUID persistedCustomerId(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT customer_id FROM users WHERE id = ?", UUID.class, userId);
    }

    private void assertCustomerLinkAudit(UUID targetUserId, UUID actorUserId, List<ExpectedLinkAudit> expected) {
        List<java.util.Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT actor_user_id, target_user_id, previous_customer_id, new_customer_id, action " +
                        "FROM user_customer_link_audit WHERE target_user_id = ? ORDER BY occurred_at, id",
                targetUserId);
        assertEquals(expected.size(), events.size());
        for (int i = 0; i < expected.size(); i++) {
            java.util.Map<String, Object> event = events.get(i);
            ExpectedLinkAudit transition = expected.get(i);
            assertEquals(actorUserId, event.get("actor_user_id"));
            assertEquals(targetUserId, event.get("target_user_id"));
            assertEquals(transition.previousCustomerId(), event.get("previous_customer_id"));
            assertEquals(transition.newCustomerId(), event.get("new_customer_id"));
            assertEquals(transition.action(), event.get("action"));
        }
    }

    private record ExpectedLinkAudit(String action, UUID previousCustomerId, UUID newCustomerId) {
    }

    @Test
    void shouldEnforceCancellationAuthorizationThroughRealJwtAndPostgres() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Customer owner = saveCustomer("cancel-owner-" + suffix);
        Customer other = saveCustomer("cancel-other-" + suffix);
        User linkedUser = saveUser("cancel-linked-" + suffix, Role.USER, owner.getId());
        User unlinkedUser = saveUser("cancel-unlinked-" + suffix, Role.USER, null);
        User admin = saveUser("cancel-admin-" + suffix, Role.ADMIN, null);

        Product product = new Product();
        product.setSku("CANCEL-AUTH-" + suffix);
        product.setName("Cancellation authorization product");
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(10);
        product.setActive(true);
        product = productRepository.saveAndFlush(product);

        String linkedToken = jwtService.generateToken(linkedUser.getEmail(), "USER");
        String unlinkedToken = jwtService.generateToken(unlinkedUser.getEmail(), "USER");
        String adminToken = jwtService.generateToken(admin.getEmail(), "ADMIN");
        String unknownUserToken = jwtService.generateToken(
                "not-persisted-" + suffix + "@example.com", "USER");

        UUID ownerOrderId = createOrderViaJwt(
                owner.getId(), product.getId(), linkedToken, "cancel-owner-key-" + suffix);
        UUID otherOrderId = createOrderViaJwt(
                other.getId(), product.getId(), adminToken, "cancel-other-key-" + suffix);
        assertEquals(8, productRepository.findById(product.getId()).orElseThrow().getStock());

        mockMvc.perform(post("/orders/{id}/cancel", ownerOrderId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/orders/{id}/cancel", ownerOrderId)
                        .header("Authorization", "Bearer " + unlinkedToken))
                .andExpect(status().isForbidden());

        String thirdPartyErrorJson = mockMvc.perform(post("/orders/{id}/cancel", otherOrderId)
                        .header("Authorization", "Bearer " + linkedToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missingOrderErrorJson = mockMvc.perform(post("/orders/{id}/cancel", UUID.randomUUID())
                        .header("Authorization", "Bearer " + linkedToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        JsonNode thirdPartyError = objectMapper.readTree(thirdPartyErrorJson);
        JsonNode missingOrderError = objectMapper.readTree(missingOrderErrorJson);
        assertEquals(thirdPartyError.get("status"), missingOrderError.get("status"));
        assertEquals(thirdPartyError.get("error"), missingOrderError.get("error"));
        assertEquals(thirdPartyError.get("message"), missingOrderError.get("message"));
        assertEquals(thirdPartyError.get("fields"), missingOrderError.get("fields"));
        assertEquals("Order not found", thirdPartyError.get("message").asText());

        mockMvc.perform(post("/orders/{id}/cancel", ownerOrderId)
                        .header("Authorization", "Bearer " + unknownUserToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
        assertEquals(OrderStatus.CREATED, orderRepository.findById(ownerOrderId).orElseThrow().getStatus());
        assertEquals(8, productRepository.findById(product.getId()).orElseThrow().getStock());

        mockMvc.perform(post("/orders/{id}/cancel", ownerOrderId)
                        .header("Authorization", "Bearer " + linkedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertEquals(OrderStatus.CANCELLED, orderRepository.findById(ownerOrderId).orElseThrow().getStatus());
        assertEquals(9, productRepository.findById(product.getId()).orElseThrow().getStock());

        mockMvc.perform(post("/orders/{id}/cancel", otherOrderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertEquals(OrderStatus.CANCELLED, orderRepository.findById(otherOrderId).orElseThrow().getStatus());
        assertEquals(10, productRepository.findById(product.getId()).orElseThrow().getStock());
    }

    private UUID createOrderViaJwt(UUID customerId, UUID productId, String token, String idempotencyKey)
            throws Exception {
        String response = mockMvc.perform(post("/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody(customerId, productId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
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

    private String orderBody(UUID customerId, UUID productId) throws Exception {
        return objectMapper.writeValueAsString(new com.github.lucasoliveira.platform.order.dto.OrderRequest(
                customerId,
                java.util.List.of(new com.github.lucasoliveira.platform.order.dto.OrderItemRequest(productId, 1))));
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
