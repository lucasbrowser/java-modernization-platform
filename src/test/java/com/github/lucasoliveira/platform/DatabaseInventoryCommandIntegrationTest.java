package com.github.lucasoliveira.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final String INVENTORY_PROVISIONER = "inventory_operator";
    private static final ObjectMapper JSON = new ObjectMapper();

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
            for (String role : List.of("app_migrator", "app_runtime", "app_bootstrap", LEGACY, READER,
                    INVENTORY_PROVISIONER)) {
                statement.execute("DROP ROLE IF EXISTS " + role);
            }
            statement.execute("CREATE ROLE " + LEGACY + " LOGIN PASSWORD '" + LEGACY_PASSWORD + "'");
            statement.execute("GRANT CREATE ON DATABASE inventory_test TO " + LEGACY);
            statement.execute("CREATE ROLE " + READER + " LOGIN PASSWORD '" + READER_PASSWORD + "'");
            statement.execute("CREATE ROLE " + INVENTORY_PROVISIONER
                    + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT");
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
    void reportsAllRoleAttributesWithExplicitValueStates() throws Exception {
        migrateTo("4");
        var report = inventory();
        Map<String, Object> provisioner = map(report.get("provisioner"));
        Map<String, Object> attributes = map(provisioner.get("attributes"));

        assertEquals("PROVISIONER", provisioner.get("roleType"));
        assertEvidence(attributes, "rolsuper", "PRESENT", false);
        assertEvidence(attributes, "rolcreaterole", "PRESENT", false);
        assertEvidence(attributes, "rolcreatedb", "PRESENT", false);
        assertEvidence(attributes, "rolreplication", "PRESENT", false);
        assertEvidence(attributes, "rolbypassrls", "PRESENT", false);
        assertEvidence(attributes, "rolinherit", "PRESENT", false);
        assertEvidence(attributes, "rolcanlogin", "PRESENT", true);
        assertEvidence(attributes, "rolvaliduntil", "NULL_VALUE", null);
        assertEvidence(attributes, "rolconfig", "NULL_VALUE", null);

        List<Object> applicationRoles = list(report.get("roles"));
        Map<String, Object> missingRuntime = applicationRoles.stream().map(DatabaseInventoryCommandIntegrationTest::map)
                .filter(role -> "app_runtime".equals(evidenceValue(role.get("roleName"))))
                .findFirst().orElseThrow();
        assertEvidence(missingRuntime, "roleExists", "PRESENT", false);
        assertEvidence(map(missingRuntime.get("attributes")), "rolsuper", "NOT_APPLICABLE", null);
    }

    @Test
    void collectsBypassRlsAndRejectsProvisionerWithItEnabled() throws Exception {
        migrateTo("4");
        executeAdmin("ALTER ROLE " + INVENTORY_PROVISIONER + " BYPASSRLS");

        var report = inventory();
        assertEvidence(map(map(report.get("provisioner")).get("attributes")), "rolbypassrls", "PRESENT", true);
        assertEquals("REJECTED", report.get("classification"));
    }

    @Test
    void distinguishesConfiguredRoleExpiryFromNoExpiry() throws Exception {
        migrateTo("4");
        executeAdmin("ALTER ROLE " + INVENTORY_PROVISIONER + " VALID UNTIL '2035-06-01 00:00:00+00'");

        var configured = inventory();
        assertEvidence(map(map(configured.get("provisioner")).get("attributes")), "rolvaliduntil",
                "PRESENT", "2035-06-01T00:00:00Z");

        executeAdmin("ALTER ROLE " + INVENTORY_PROVISIONER + " VALID UNTIL NULL");
        var noExpiry = inventory();
        assertEvidence(map(map(noExpiry.get("provisioner")).get("attributes")), "rolvaliduntil", "NULL_VALUE", null);
    }

    @Test
    void redactsEveryNonEmptyRoleConfigAndDoesNotClassifyItAsSafe() throws Exception {
        migrateTo("4");
        String marker = "inventory-secret-marker-should-not-appear";
        executeAdmin("ALTER ROLE " + INVENTORY_PROVISIONER + " SET application_name TO '" + marker + "'");

        var report = inventory();
        String json = JSON.writeValueAsString(report);
        assertEvidence(map(map(report.get("provisioner")).get("attributes")), "rolconfig", "NOT_VERIFIABLE", null);
        assertFalse(json.contains(marker));
        assertFalse(report.toString().contains(marker));
        assertEquals("UNKNOWN", report.get("classification"));
    }

    @Test
    void missingProvisionerConfigurationIsNotInferredFromConnectionUser() throws Exception {
        migrateTo("4");
        var report = DatabaseInventoryCommand.inspect(postgres.getJdbcUrl(), READER, READER_PASSWORD, null);
        Map<String, Object> provisioner = map(report.get("provisioner"));

        assertEvidence(provisioner, "roleName", "NOT_VERIFIABLE", null);
        assertEvidence(provisioner, "roleExists", "NOT_VERIFIABLE", null);
        assertEquals("UNKNOWN", report.get("classification"));
        assertFalse(report.toString().contains(READER));
    }

    @Test
    void configuredButMissingProvisionerIsDistinctFromNullAttributes() throws Exception {
        migrateTo("4");
        var report = DatabaseInventoryCommand.inspect(postgres.getJdbcUrl(), READER, READER_PASSWORD,
                "missing_inventory_operator");
        Map<String, Object> provisioner = map(report.get("provisioner"));

        assertEvidence(provisioner, "roleName", "PRESENT", "missing_inventory_operator");
        assertEvidence(provisioner, "roleExists", "PRESENT", false);
        assertEvidence(map(provisioner.get("attributes")), "rolsuper", "NOT_APPLICABLE", null);
        assertEquals("REJECTED", report.get("classification"));
    }

    @Test
    void allCollectionStatesSerializeWithoutLosingFalseOrNull() throws Exception {
        Map<String, Object> states = Map.of(
                "presentFalse", DatabaseInventoryCommand.CollectionEvidence.present(false),
                "nullValue", DatabaseInventoryCommand.CollectionEvidence.nullValue("database value is null"),
                "notApplicable", DatabaseInventoryCommand.CollectionEvidence.notApplicable("role absent"),
                "notCollected", DatabaseInventoryCommand.CollectionEvidence.notCollected("collector disabled"),
                "notVerifiable", DatabaseInventoryCommand.CollectionEvidence.notVerifiable("catalog not visible"));

        JsonNode json = JSON.readTree(JSON.writeValueAsBytes(states));
        assertEquals("PRESENT", json.path("presentFalse").path("state").asText());
        assertFalse(json.path("presentFalse").path("value").asBoolean(true));
        assertEquals("NULL_VALUE", json.path("nullValue").path("state").asText());
        assertTrue(json.path("nullValue").path("value").isNull());
        assertEquals("NOT_APPLICABLE", json.path("notApplicable").path("state").asText());
        assertEquals("NOT_COLLECTED", json.path("notCollected").path("state").asText());
        assertEquals("NOT_VERIFIABLE", json.path("notVerifiable").path("state").asText());
    }

    @Test
    void unavailableSecurityAttributeStatesCannotPassTargetRoleValidation() throws Exception {
        migrateTo("5");
        var report = inventory();
        List<Map<String, Object>> roles = list(report.get("roles")).stream()
                .map(DatabaseInventoryCommandIntegrationTest::map).toList();
        assertTrue(DatabaseInventoryCommand.validTargetRoles(roles));

        Map<String, Object> runtime = roles.stream()
                .filter(role -> "app_runtime".equals(evidenceValue(role.get("roleName"))))
                .findFirst().orElseThrow();
        map(runtime.get("attributes")).put("rolsuper",
                DatabaseInventoryCommand.CollectionEvidence.notCollected("test missing collection"));
        assertFalse(DatabaseInventoryCommand.validTargetRoles(roles));

        map(runtime.get("attributes")).put("rolsuper",
                DatabaseInventoryCommand.CollectionEvidence.notVerifiable("test query failure"));
        assertFalse(DatabaseInventoryCommand.validTargetRoles(roles));
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
        executeAdmin("INSERT INTO public.customers (id, name, email, document) VALUES "
                + "('c89bf3c1-cf25-4f62-a598-e151f1c42520', 'Inventory Customer', "
                + "'inventory-customer@example.test', 'inventory-document')");
        executeAdmin("INSERT INTO public.users (id, name, email, password, role) VALUES "
                + "('989bf3c1-cf25-4f62-a598-e151f1c42520', 'Inventory User', "
                + "'inventory-user@example.test', 'test-only-hash', 'USER')");
        String stateBefore = databaseStateSnapshot();
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
        assertEquals(stateBefore, databaseStateSnapshot());
        assertEquals(5, count("SELECT count(*) FROM public.flyway_schema_history"));
        assertEquals(1, count("SELECT count(*) FROM public.users"));
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
        return DatabaseInventoryCommand.inspect(postgres.getJdbcUrl(), READER, READER_PASSWORD,
                INVENTORY_PROVISIONER);
    }

    private String databaseStateSnapshot() throws Exception {
        String sql = "SELECT jsonb_build_object("
                + "'database', (SELECT jsonb_build_object('oid', oid, 'owner', datdba, 'acl', datacl) "
                + "FROM pg_database WHERE datname=current_database()), "
                + "'roles', (SELECT jsonb_agg(jsonb_build_object('name',rolname,'login',rolcanlogin,'super',rolsuper,"
                + "'createRole',rolcreaterole,'createDb',rolcreatedb,'replication',rolreplication,"
                + "'bypassRls',rolbypassrls,'inherit',rolinherit,'validUntil',rolvaliduntil,'config',rolconfig) "
                + "ORDER BY rolname) FROM pg_roles), "
                + "'memberships', (SELECT jsonb_agg(jsonb_build_object('role',r.rolname,'member',m.member,"
                + "'grantor',m.grantor,'admin',m.admin_option,'inherit',m.inherit_option,'set',m.set_option) "
                + "ORDER BY m.roleid,m.member) FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid), "
                + "'schemas', (SELECT jsonb_agg(jsonb_build_object('oid',oid,'name',nspname,'owner',nspowner,'acl',nspacl) "
                + "ORDER BY oid) FROM pg_namespace), "
                + "'relations', (SELECT jsonb_agg(jsonb_build_object('oid',c.oid,'schema',n.nspname,'name',c.relname,"
                + "'owner',c.relowner,'acl',c.relacl,'kind',c.relkind) ORDER BY c.oid) FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid=c.relnamespace), "
                + "'functions', (SELECT jsonb_agg(jsonb_build_object('oid',p.oid,'name',p.proname,'owner',p.proowner,"
                + "'acl',p.proacl) ORDER BY p.oid) FROM pg_proc p), "
                + "'customers', (SELECT jsonb_agg(to_jsonb(c) ORDER BY c.id) FROM public.customers c), "
                + "'users', (SELECT jsonb_agg(to_jsonb(u)-'password' ORDER BY u.id) FROM public.users u), "
                + "'history', (SELECT jsonb_agg(to_jsonb(h) ORDER BY h.installed_rank) FROM public.flyway_schema_history h)"
                + ")::text";
        try (Connection admin = adminConnection(); Statement statement = admin.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
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

    private static Object evidenceValue(Object value) {
        return value instanceof DatabaseInventoryCommand.CollectionEvidence<?> evidence ? evidence.value() : null;
    }

    private static void assertEvidence(Map<String, Object> container, String key, String expectedState,
            Object expectedValue) {
        Object value = container.get(key);
        assertTrue(value instanceof DatabaseInventoryCommand.CollectionEvidence<?>,
                () -> key + " should be represented by collection evidence");
        var evidence = (DatabaseInventoryCommand.CollectionEvidence<?>) value;
        assertEquals(expectedState, evidence.state().name());
        assertEquals(expectedValue, evidence.value());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
