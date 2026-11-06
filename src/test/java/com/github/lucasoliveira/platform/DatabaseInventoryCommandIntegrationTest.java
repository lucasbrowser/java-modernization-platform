package com.github.lucasoliveira.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseInventoryCommandIntegrationTest {

    private static final String LEGACY = "legacy_owner";
    private static final String LEGACY_PASSWORD = "legacy-owner-test-password";
    private static final String READER = "inventory_reader";
    private static final String READER_PASSWORD = "inventory-reader-test-password";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("inventory_test")
            .withUsername("provisioner")
            .withPassword("provisioner-test-password");

    @BeforeEach
    void resetDisposableDatabase() throws Exception {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
            statement.execute("GRANT CREATE ON SCHEMA public TO PUBLIC");
            if (roleExists(admin, LEGACY)) {
                statement.execute("REVOKE CREATE ON DATABASE inventory_test FROM " + LEGACY);
            }
            if (roleExists(admin, READER)) {
                statement.execute("REVOKE pg_read_all_data FROM " + READER);
            }
            for (String role : List.of("app_migrator", "app_runtime", "app_bootstrap", LEGACY, READER)) {
                statement.execute("DROP ROLE IF EXISTS " + role);
            }
            statement.execute("CREATE ROLE " + LEGACY + " LOGIN PASSWORD '" + LEGACY_PASSWORD + "'");
            statement.execute("GRANT CREATE ON DATABASE inventory_test TO " + LEGACY);
            statement.execute("CREATE ROLE " + READER + " LOGIN PASSWORD '" + READER_PASSWORD + "'");
            statement.execute("GRANT pg_read_all_data TO " + READER);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + READER);
            statement.execute("CREATE EXTENSION pgcrypto WITH SCHEMA public");
            statement.execute("GRANT CREATE ON SCHEMA public TO " + LEGACY);
            statement.execute("ALTER SCHEMA public OWNER TO " + LEGACY);
        }
    }

    @Test
    void classifiesV1ThroughV4WithSingleLegacyOwnerAsSupportedCandidate() throws Exception {
        migrateTo("4");
        var report = inventory();
        assertEquals("SUPPORTED_V1_V4", report.get("classification"), report.toString());
        assertEquals("V1_V4", map(report.get("flyway")).get("state"));
        assertEquals(LEGACY, report.get("applicationOwner"));
        assertTrue(list(report.get("blockers")).isEmpty());
        assertEquals("NOT_VERIFIABLE", map(report.get("consumers")).get("status"));
        assertEquals("NOT_PROVIDED", map(report.get("backupEvidence")).get("status"));
    }

    @Test
    void classifiesV1ThroughV5WithSingleLegacyOwnerAsSupportedCandidate() throws Exception {
        migrateTo("5");
        var report = inventory();
        assertEquals("SUPPORTED_V1_V5", report.get("classification"));
        assertEquals("V1_V5", map(report.get("flyway")).get("state"));
        assertEquals(LEGACY, report.get("applicationOwner"));
        assertTrue(list(report.get("blockers")).isEmpty());
    }

    @Test
    void rejectsAbsentHistory() throws Exception {
        var report = inventory();
        assertEquals("REJECTED", report.get("classification"));
        assertEquals("ABSENT", map(report.get("flyway")).get("state"));
    }

    @Test
    void rejectsChecksumDivergence() throws Exception {
        migrateTo("4");
        executeAdmin("UPDATE public.flyway_schema_history SET checksum=checksum+1 WHERE version='2'");
        var report = inventory();
        assertEquals("REJECTED", report.get("classification"));
        assertEquals("DIVERGENT", map(report.get("flyway")).get("state"));
    }

    @Test
    void rejectsFailedMigrationHistory() throws Exception {
        migrateTo("4");
        executeAdmin("INSERT INTO public.flyway_schema_history (installed_rank, version, description, type, script, "
                + "installed_by, execution_time, success) VALUES (5, '5', 'failed fixture', 'SQL', 'V5_failed.sql', "
                + "'fixture', 0, false)");
        var report = inventory();
        assertEquals("REJECTED", report.get("classification"));
        assertEquals("FAILED", map(report.get("flyway")).get("state"));
    }

    @Test
    void rejectsMixedOwnership() throws Exception {
        migrateTo("4");
        executeAdmin("ALTER TABLE public.products OWNER TO " + READER);
        var report = inventory();
        assertEquals("REJECTED", report.get("classification"));
        assertEquals(false, report.get("ownershipUniform"));
    }

    @Test
    void rejectsPartiallyProvisionedRoles() throws Exception {
        migrateTo("4");
        executeAdmin("CREATE ROLE app_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
        var report = inventory();
        assertEquals("REJECTED", report.get("classification"));
        assertTrue(list(report.get("blockers")).stream().anyMatch(value -> value.toString().contains("part of the target role set")));
    }

    @Test
    void rejectsAdditionalObjects() throws Exception {
        migrateTo("4");
        executeAdmin("CREATE TABLE public.legacy_notes (id integer)");
        var report = inventory();
        assertEquals("REJECTED", report.get("classification"));
        assertTrue(list(report.get("unclassifiedObjects")).stream().anyMatch(value -> value.toString().contains("legacy_notes")));
    }

    @Test
    void rejectsMissingOrDivergentProtections() throws Exception {
        migrateTo("5");
        executeAdmin("ALTER TABLE public.users DISABLE TRIGGER trg_prevent_runtime_admin_user_write");
        var report = inventory();
        assertEquals("REJECTED", report.get("classification"));
        assertFalse(Boolean.TRUE.equals(map(report.get("protections")).get("match")));
    }

    @Test
    void rejectsDivergentProtectionFunctionDefinition() throws Exception {
        migrateTo("5");
        executeAdmin("CREATE OR REPLACE FUNCTION public.prevent_runtime_admin_user_write() "
                + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$");
        var report = inventory();
        assertEquals("REJECTED", report.get("classification"));
        assertFalse(Boolean.TRUE.equals(map(report.get("protections")).get("match")));
    }

    @Test
    void repeatedInventoryIsReadOnlyAndEquivalentForStableDatabase() throws Exception {
        migrateTo("5");
        var first = inventory();
        var second = inventory();
        assertEquals(first.get("classification"), second.get("classification"));
        assertEquals(first.get("server"), second.get("server"));
        assertEquals(first.get("schemas"), second.get("schemas"));
        assertEquals(first.get("roles"), second.get("roles"));
        assertEquals(first.get("effectiveGrants"), second.get("effectiveGrants"));
        assertEquals(first.get("objects"), second.get("objects"));
        assertEquals(first.get("extensions"), second.get("extensions"));
        assertEquals(first.get("flyway"), second.get("flyway"));
        assertEquals(first.get("protections"), second.get("protections"));
        assertEquals(first.get("dependencies"), second.get("dependencies"));
        assertEquals(first.get("data"), second.get("data"));
        assertEquals(5, count("SELECT count(*) FROM public.flyway_schema_history"));
        assertEquals(0, count("SELECT count(*) FROM public.users"));
        assertEquals(3, count("SELECT count(*) FROM pg_roles WHERE rolname LIKE 'app_%'"));
    }

    private void migrateTo(String target) throws Exception {
        if ("5".equals(target)) {
            provisionTargetRoles();
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), LEGACY, LEGACY_PASSWORD)
                .locations("classpath:db/migration").schemas("public").defaultSchema("public")
                .createSchemas(false).target(target).load().migrate();
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + READER);
        }
    }

    private void provisionTargetRoles() throws Exception {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            for (String role : List.of("app_migrator", "app_runtime", "app_bootstrap")) {
                statement.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                        + "NOINHERIT NOREPLICATION PASSWORD '" + role + "-test-password'");
            }
        }
    }

    private boolean roleExists(Connection connection, String role) throws Exception {
        try (var statement = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=?)")) {
            statement.setString(1, role);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private Map<String, Object> inventory() throws Exception {
        return DatabaseInventoryCommand.inspect(postgres.getJdbcUrl(), READER, READER_PASSWORD);
    }

    private void executeAdmin(String sql) throws Exception {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String sql) throws Exception {
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private Connection adminConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
