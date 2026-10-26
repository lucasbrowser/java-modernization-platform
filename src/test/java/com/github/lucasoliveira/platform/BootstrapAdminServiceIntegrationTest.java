package com.github.lucasoliveira.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.lucasoliveira.platform.bootstrap.BootstrapAdminService;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class BootstrapAdminServiceIntegrationTest {
    private static final String PROVISIONER_PASSWORD = "test-provisioner-password";
    private static final String BOOTSTRAP_PASSWORD = "test-bootstrap-password";
    private static final String MIGRATOR_PASSWORD = "test-migrator-password";
    private static final String RUNTIME_PASSWORD = "test-runtime-password";
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bootstrap_test").withUsername("provisioner").withPassword(PROVISIONER_PASSWORD);

    @BeforeAll
    static void prepareDatabase() throws Exception {
        postgres.copyFileToContainer(MountableFile.forHostPath(Path.of("scripts/provision-postgres-roles.sql").toAbsolutePath()),
                "/tmp/provision-postgres-roles.sql");
        var provision = postgres.execInContainer("sh", "-c", "MIGRATION_DB_PASSWORD='" + MIGRATOR_PASSWORD
                + "' RUNTIME_DB_PASSWORD='" + RUNTIME_PASSWORD + "' BOOTSTRAP_DB_PASSWORD='" + BOOTSTRAP_PASSWORD
                + "' psql -v ON_ERROR_STOP=1 -U provisioner -d bootstrap_test -f /tmp/provision-postgres-roles.sql");
        assertEquals(0, provision.getExitCode(), provision.getStderr());
        DatabaseMigrationJob.migrate(postgres.getJdbcUrl(), "app_migrator", MIGRATOR_PASSWORD);
    }

    @BeforeEach
    void clearUsers() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "provisioner", PROVISIONER_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM users");
        }
    }

    @Test
    void createsAdminAndSameEmailIsIdempotentWithoutChangingPasswordOrRole() throws Exception {
        var service = service();
        assertEquals(BootstrapAdminService.Result.CREATED,
                service.createFirstAdmin("Admin", "Admin@Example.test", "initial-pass"));
        String initialHash = passwordHash();
        assertEquals(BootstrapAdminService.Result.NO_OP,
                service.createFirstAdmin("Changed", "ADMIN@example.test", "different-pass"));
        assertEquals(initialHash, passwordHash());
        assertEquals(1, adminCount());
        assertFalse(new BCryptPasswordEncoder().matches("different-pass", initialHash));
    }

    @Test
    void refusesUserEmailAndDifferentExistingAdminWithoutMutation() throws Exception {
        insert("USER", "user@example.test", "untouched-user-hash");
        assertEquals(BootstrapAdminService.Result.CONFLICT,
                service().createFirstAdmin("Other", "USER@example.test", "password123"));
        assertEquals("USER", role("user@example.test"));
        assertEquals("untouched-user-hash", passwordHash("user@example.test"));

        insert("ADMIN", "admin@example.test", "untouched-admin-hash");
        assertEquals(BootstrapAdminService.Result.CONFLICT,
                service().createFirstAdmin("Other", "second@example.test", "password123"));
        assertEquals("untouched-admin-hash", passwordHash("admin@example.test"));
        assertEquals(1, adminCount());
    }

    @Test
    void concurrentSameAndDifferentEmailsNeverCreateMoreThanOneAdmin() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service().createFirstAdmin("Admin", "same@example.test", "password123"));
            var second = executor.submit(() -> service().createFirstAdmin("Admin", "same@example.test", "password123"));
            List<BootstrapAdminService.Result> sameResults = List.of(first.get(), second.get());
            assertTrue(sameResults.contains(BootstrapAdminService.Result.CREATED));
            assertTrue(sameResults.contains(BootstrapAdminService.Result.NO_OP));
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), "provisioner", PROVISIONER_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM users WHERE role = 'ADMIN'");
        }
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service().createFirstAdmin("Admin", "first@example.test", "password123"));
            var second = executor.submit(() -> service().createFirstAdmin("Admin", "second@example.test", "password123"));
            List<BootstrapAdminService.Result> differentResults = List.of(first.get(), second.get());
            assertEquals(1, differentResults.stream().filter(r -> r == BootstrapAdminService.Result.CREATED).count());
            assertEquals(1, differentResults.stream().filter(r -> r == BootstrapAdminService.Result.CONFLICT).count());
        }
        assertEquals(1, adminCount());
    }

    private static BootstrapAdminService service() {
        return new BootstrapAdminService(postgres.getJdbcUrl(), "app_bootstrap", BOOTSTRAP_PASSWORD,
                new BCryptPasswordEncoder());
    }
    private static int adminCount() throws Exception {
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "provisioner", PROVISIONER_PASSWORD);
                var s = c.createStatement(); var rs = s.executeQuery("SELECT count(*) FROM users WHERE role='ADMIN'")) {
            rs.next(); return rs.getInt(1);
        }
    }
    private static String passwordHash() throws Exception { return passwordHash("admin@example.test"); }
    private static String passwordHash(String email) throws Exception {
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "provisioner", PROVISIONER_PASSWORD);
                var p = c.prepareStatement("SELECT password FROM users WHERE lower(email)=lower(?)")) {
            p.setString(1, email); try (var rs = p.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }
    private static String role(String email) throws Exception {
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "provisioner", PROVISIONER_PASSWORD);
                var p = c.prepareStatement("SELECT role FROM users WHERE email=?")) {
            p.setString(1, email); try (var rs = p.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }
    private static void insert(String role, String email, String hash) throws Exception {
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "provisioner", PROVISIONER_PASSWORD);
                var p = c.prepareStatement("INSERT INTO users(id,name,email,password,role) VALUES(?,?,?,?,?)")) {
            p.setObject(1, UUID.randomUUID()); p.setString(2, "Existing"); p.setString(3, email);
            p.setString(4, hash); p.setString(5, role); p.executeUpdate();
        }
    }
}
