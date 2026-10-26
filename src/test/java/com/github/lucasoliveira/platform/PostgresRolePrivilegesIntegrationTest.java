package com.github.lucasoliveira.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresRolePrivilegesIntegrationTest {

    private static final String MIGRATOR = "app_migrator";
    private static final String RUNTIME = "app_runtime";
    private static final String BOOTSTRAP = "app_bootstrap";
    private static final String MIGRATOR_PASSWORD = "test-migrator-password";
    private static final String RUNTIME_PASSWORD = "test-runtime-password";
    private static final String BOOTSTRAP_PASSWORD = "test-bootstrap-password";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("roles_test")
            .withUsername("provisioner")
            .withPassword("test-provisioner-password");

    @BeforeAll
    static void provisionAndMigrate() throws Exception {
        postgres.copyFileToContainer(
                MountableFile.forHostPath(Path.of("scripts/provision-postgres-roles.sql").toAbsolutePath()),
                "/tmp/provision-postgres-roles.sql");
        String provisionCommand = "MIGRATION_DB_PASSWORD='" + MIGRATOR_PASSWORD + "' RUNTIME_DB_PASSWORD='"
                + RUNTIME_PASSWORD + "' BOOTSTRAP_DB_PASSWORD='" + BOOTSTRAP_PASSWORD
                + "' psql -v ON_ERROR_STOP=1 -U provisioner -d roles_test -f /tmp/provision-postgres-roles.sql";
        var provisioning = postgres.execInContainer("sh", "-c", provisionCommand);
        assertEquals(0, provisioning.getExitCode(), provisioning.getStderr());

        DatabaseMigrationJob.migrate(postgres.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD);
    }

    @Test
    void nonSuperuserMigratorAppliesAllMigrationsAndOwnsProtection() throws Exception {
        try (Connection migrator = connection(MIGRATOR, MIGRATOR_PASSWORD);
                Statement statement = migrator.createStatement()) {
            assertTrue(scalarBoolean(statement, "SELECT NOT rolsuper FROM pg_roles WHERE rolname = current_user"));
            assertEquals(MIGRATOR, scalarString(statement, "SELECT session_user"));
            assertTrue(scalarBoolean(statement,
                    "SELECT rolsuper FROM pg_roles WHERE rolname = 'provisioner'"));
            assertEquals(5, scalarInt(statement, "SELECT max(version)::int FROM flyway_schema_history WHERE success"));
            assertEquals(1, scalarInt(statement,
                    "SELECT count(*)::int FROM pg_extension WHERE extname = 'pgcrypto'"));
            assertFalse(scalarBoolean(statement, "SELECT rolsuper OR rolcreatedb OR rolcreaterole "
                    + "FROM pg_roles WHERE rolname = current_user"));
            assertEquals(MIGRATOR, scalarString(statement,
                    "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE proname = 'prevent_runtime_admin_user_write'"));
            assertEquals(MIGRATOR, scalarString(statement,
                    "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = 'users'"));
        }
    }

    @Test
    void runtimeCanInsertUserButCannotInsertOrPromoteAdmin() throws Exception {
        String userId = UUID.randomUUID().toString();
        try (Connection runtime = connection(RUNTIME, RUNTIME_PASSWORD); Statement statement = runtime.createStatement()) {
            assertEquals(RUNTIME, scalarString(statement, "SELECT session_user"));
            statement.execute("INSERT INTO users (id, name, email, password, role) VALUES ('" + userId
                    + "', 'Runtime User', 'runtime-" + userId + "@example.test', 'hash', 'USER')");
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO users "
                    + "(name, email, password, role) VALUES ('Admin', 'runtime-admin-" + userId
                    + "@example.test', 'hash', 'ADMIN')"));
            assertThrows(SQLException.class, () -> statement.execute("UPDATE users SET role = 'ADMIN' WHERE id = '"
                    + userId + "'"));
        }
    }

    @Test
    void bootstrapIsTheOnlyNonOwnerIdentityThatCanInsertAdminAndHasNoRuntimeMembership() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        try (Connection bootstrap = connection(BOOTSTRAP, BOOTSTRAP_PASSWORD);
                Statement statement = bootstrap.createStatement()) {
            statement.execute("INSERT INTO users (name, email, password, role) VALUES ('Bootstrap Admin', 'bootstrap-"
                    + suffix + "@example.test', 'hash', 'ADMIN')");
            assertFalse(scalarBoolean(statement,
                    "SELECT pg_has_role('" + RUNTIME + "', '" + BOOTSTRAP + "', 'MEMBER')"));
        }
    }

    @Test
    void runtimeCanLinkCustomerAndAppendAuditButCannotMutateAuditOrDeleteData() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        String userId = UUID.randomUUID().toString();
        String customerId = UUID.randomUUID().toString();
        String auditId = UUID.randomUUID().toString();
        try (Connection runtime = connection(RUNTIME, RUNTIME_PASSWORD); Statement statement = runtime.createStatement()) {
            statement.execute("INSERT INTO users (id, name, email, password, role) VALUES ('" + userId
                    + "', 'Link User', 'link-" + suffix + "@example.test', 'hash', 'USER')");
            statement.execute("INSERT INTO customers (id, name, email, document) VALUES ('" + customerId
                    + "', 'Customer', 'customer-" + suffix + "@example.test', 'doc-" + suffix + "')");
            statement.execute("UPDATE users SET customer_id = '" + customerId + "' WHERE id = '" + userId + "'");
            statement.execute("INSERT INTO user_customer_link_audit (id, actor_user_id, target_user_id, "
                    + "previous_customer_id, new_customer_id, action, justification) VALUES ('" + auditId + "', '"
                    + userId + "', '" + userId + "', NULL, '" + customerId + "', 'CREATE', 'test')");
            assertThrows(SQLException.class, () -> statement.execute("UPDATE user_customer_link_audit "
                    + "SET justification = 'tampered' WHERE id = '" + auditId + "'"));
            assertThrows(SQLException.class, () -> statement.execute("DELETE FROM user_customer_link_audit "
                    + "WHERE id = '" + auditId + "'"));
            assertThrows(SQLException.class, () -> statement.execute("DELETE FROM users WHERE id = '" + userId + "'"));
            assertThrows(SQLException.class, () -> statement.execute("TRUNCATE users"));
            assertEquals(customerId, scalarString(statement,
                    "SELECT customer_id::text FROM users WHERE id = '" + userId + "'"));
            assertEquals(1, scalarInt(statement,
                    "SELECT count(*)::int FROM user_customer_link_audit WHERE id = '" + auditId + "'"));
        }
    }

    @Test
    void runtimeCannotChangeSchemaRolesProtectionOrCreateDatabase() throws Exception {
        try (Connection runtime = connection(RUNTIME, RUNTIME_PASSWORD); Statement statement = runtime.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("CREATE TABLE public.runtime_table (id int)"));
            assertThrows(SQLException.class, () -> statement.execute("ALTER TABLE users DISABLE TRIGGER "
                    + "trg_prevent_runtime_admin_user_write"));
            assertThrows(SQLException.class, () -> statement.execute("DROP FUNCTION "
                    + "prevent_runtime_admin_user_write()"));
            assertThrows(SQLException.class, () -> statement.execute("DROP TRIGGER "
                    + "trg_prevent_runtime_admin_user_write ON users"));
            assertThrows(SQLException.class, () -> statement.execute("SET session_replication_role = replica"));
            assertThrows(SQLException.class,
                    () -> statement.execute("SET SESSION AUTHORIZATION " + BOOTSTRAP));
            assertThrows(SQLException.class, () -> statement.execute("CREATE ROLE runtime_escalation"));
            assertThrows(SQLException.class, () -> statement.execute("CREATE DATABASE runtime_escalation"));
            assertThrows(SQLException.class,
                    () -> statement.execute("ALTER ROLE " + RUNTIME + " SUPERUSER"));
            assertThrows(SQLException.class, () -> statement.execute("SET ROLE " + MIGRATOR));
            assertThrows(SQLException.class, () -> statement.execute("SET ROLE " + BOOTSTRAP));
            assertFalse(scalarBoolean(statement, "SELECT rolsuper OR rolcreatedb OR rolcreaterole "
                    + "FROM pg_roles WHERE rolname = current_user"));
        }
    }

    @Test
    void runtimeApiContextIsBlockedOnlyByTheAuthorizedStartupAdminSeed() {
        SpringApplication application = new SpringApplication(JavaModernizationPlatformApplication.class);
        application.setDefaultProperties(java.util.Map.ofEntries(
                java.util.Map.entry("spring.main.banner-mode", "off"),
                java.util.Map.entry("server.port", "0"),
                java.util.Map.entry("management.server.port", "0"),
                java.util.Map.entry("spring.flyway.enabled", "false"),
                java.util.Map.entry("DB_URL", postgres.getJdbcUrl()),
                java.util.Map.entry("DB_USERNAME", RUNTIME),
                java.util.Map.entry("DB_PASSWORD", RUNTIME_PASSWORD),
                java.util.Map.entry("spring.datasource.driver-class-name", "org.postgresql.Driver"),
                java.util.Map.entry("spring.jpa.database-platform", "org.hibernate.dialect.PostgreSQLDialect"),
                java.util.Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
                java.util.Map.entry("JWT_SECRET", "test-only-secret-that-is-at-least-32-bytes-long"),
                java.util.Map.entry("PROMETHEUS_PASSWORD", "test-metrics-password"),
                java.util.Map.entry("logging.level.root", "ERROR")));

        RuntimeException startupFailure = assertThrows(RuntimeException.class,
                () -> application.run("--server.port=0", "--management.server.port=0"));
        Throwable cause = startupFailure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause.getMessage().contains("only the bootstrap role may create ADMIN"),
                "Runtime API startup failed for another reason: " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
    }

    private static Connection connection(String user, String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
    }

    private static String scalarString(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static int scalarInt(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static boolean scalarBoolean(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }
}
