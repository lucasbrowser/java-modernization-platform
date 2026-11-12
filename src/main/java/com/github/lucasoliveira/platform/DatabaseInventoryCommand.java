package com.github.lucasoliveira.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only database inventory entry point for existing PostgreSQL databases. */
public final class DatabaseInventoryCommand {

    private static final List<String> BUSINESS_TABLES = List.of(
            "users", "customers", "products", "orders", "order_items", "user_customer_link_audit");
    private static final List<String> APP_ROLES = List.of("app_migrator", "app_runtime", "app_bootstrap");
    private static final ObjectMapper JSON = new ObjectMapper();

    enum CollectionState {
        PRESENT,
        NULL_VALUE,
        NOT_APPLICABLE,
        NOT_COLLECTED,
        NOT_VERIFIABLE
    }

    record CollectionEvidence<T>(CollectionState state, T value, String reason) {
        static <T> CollectionEvidence<T> present(T value) {
            return new CollectionEvidence<>(CollectionState.PRESENT, Objects.requireNonNull(value), null);
        }

        static <T> CollectionEvidence<T> nullValue(String reason) {
            return new CollectionEvidence<>(CollectionState.NULL_VALUE, null, reason);
        }

        static <T> CollectionEvidence<T> notApplicable(String reason) {
            return new CollectionEvidence<>(CollectionState.NOT_APPLICABLE, null, reason);
        }

        static <T> CollectionEvidence<T> notCollected(String reason) {
            return new CollectionEvidence<>(CollectionState.NOT_COLLECTED, null, reason);
        }

        static <T> CollectionEvidence<T> notVerifiable(String reason) {
            return new CollectionEvidence<>(CollectionState.NOT_VERIFIABLE, null, reason);
        }
    }

    private DatabaseInventoryCommand() {
    }

    public static int runFromEnvironment() {
        try {
            String url = required("INVENTORY_DB_URL");
            String username = required("INVENTORY_DB_USERNAME");
            String password = required("INVENTORY_DB_PASSWORD");
            Map<String, Object> report = inspect(url, username, password,
                    optional("INVENTORY_PROVISIONER_ROLE"));
            System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return "SUPPORTED_V1_V4".equals(report.get("classification"))
                    || "SUPPORTED_V1_V5".equals(report.get("classification")) ? 0 : 3;
        } catch (Exception exception) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("classification", "UNKNOWN");
            report.put("readOnly", true);
            report.put("verificationStatus", "NOT_VERIFIABLE");
            report.put("blockers", List.of("Required inventory information could not be verified."));
            report.put("errorType", exception.getClass().getSimpleName());
            try {
                System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            } catch (Exception serializationException) {
                System.err.println("Inventory could not produce a report; no database changes were requested.");
            }
            return 1;
        }
    }

    static Map<String, Object> inspect(String url, String username, String password) throws Exception {
        return inspect(url, username, password, null);
    }

    static Map<String, Object> inspect(String url, String username, String password,
            String provisionerRoleName) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        List<String> blockers = new ArrayList<>();
        boolean roleEvidenceUnknown = false;
        report.put("classification", "UNKNOWN");
        report.put("readOnly", true);
        report.put("operationalPrerequisites", Map.of(
                "backupRestorationConfirmed", false,
                "consumerCredentialsReviewed", false,
                "apiStoppedOrUsingRuntimeOnly", false,
                "note", "These facts require operator evidence and cannot be verified from database catalogs."));

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY");
            }
            try {
                report.put("server", queryOne(connection, "SELECT version() AS fullVersion, "
                        + "current_setting('server_version_num') AS versionNum, "
                        + "current_database() AS database, session_user AS sessionUser, "
                        + "current_schemas(true) AS searchPathSchemas"));
                Map<String, Object> server = castMap(report.get("server"));
                String versionNum = String.valueOf(server.get("versionnum"));
                if (!versionNum.startsWith("16")) {
                    blockers.add("PostgreSQL 16 is required.");
                }

                Map<String, Object> history = flywayHistory(url, username, password, connection);
                report.put("flyway", history);
                String migrationState = String.valueOf(history.get("state"));
                if (!List.of("V1_V4", "V1_V5").contains(migrationState)) {
                    blockers.add("Flyway history is absent, incomplete, failed, unknown, or divergent.");
                }

                report.put("schemas", query(connection, "SELECT n.nspname AS schema, "
                        + "pg_get_userbyid(n.nspowner) AS owner, n.nspacl::text AS acl "
                        + "FROM pg_namespace n WHERE n.nspname NOT LIKE 'pg_toast%' "
                        + "ORDER BY n.nspname"));
                List<Map<String, Object>> schemas = castList(report.get("schemas"));
                List<String> extraSchemas = schemas.stream().map(row -> String.valueOf(row.get("schema")))
                        .filter(schema -> !List.of("public", "pg_catalog", "information_schema").contains(schema)
                                && !schema.startsWith("pg_temp_") && !schema.startsWith("pg_toast_temp_"))
                        .toList();
                report.put("unclassifiedSchemas", extraSchemas);
                if (!extraSchemas.isEmpty()) {
                    blockers.add("Additional schemas are outside the supported inventory scope.");
                }
                List<Map<String, Object>> roles;
                Map<String, Object> provisioner;
                try {
                    roles = roleInventory(connection);
                    provisioner = provisionerInventory(connection, provisionerRoleName);
                } catch (SQLException roleQueryFailure) {
                    roles = unverifiableApplicationRoles("Role attributes could not be verified.");
                    provisioner = unverifiableProvisioner(provisionerRoleName,
                            "Provisioner role attributes could not be verified.");
                    roleEvidenceUnknown = true;
                    blockers.add("Role attributes or memberships could not be verified.");
                }
                report.put("roles", roles);
                report.put("provisioner", provisioner);

                boolean targetRoleExistenceKnown = roles.stream().allMatch(role -> evidenceState(role.get("roleExists"))
                        == CollectionState.PRESENT);
                if (!targetRoleExistenceKnown) {
                    roleEvidenceUnknown = true;
                    blockers.add("Target role existence cannot be established.");
                } else {
                    long existingTargetRoles = roles.stream().filter(DatabaseInventoryCommand::roleExists).count();
                    if (existingTargetRoles != 0 && existingTargetRoles != APP_ROLES.size()) {
                        blockers.add("Only part of the target role set exists; manual diagnosis is required.");
                    } else if (existingTargetRoles == APP_ROLES.size() && !validTargetRoles(roles)) {
                        blockers.add("Existing target roles have attributes or memberships outside the approved profile.");
                        roleEvidenceUnknown |= hasUnverifiableAttributes(roles);
                    } else if ("V1_V4".equals(migrationState) && existingTargetRoles == APP_ROLES.size()) {
                        blockers.add("V1-V4 with a fully provisioned target role set is outside the initial supported states.");
                    } else if ("V1_V5".equals(migrationState) && existingTargetRoles != APP_ROLES.size()) {
                        blockers.add("V1-V5 requires the complete validated target role set.");
                    }
                }
                if (CollectionState.NOT_VERIFIABLE == evidenceState(provisioner.get("roleName"))) {
                    roleEvidenceUnknown = true;
                    blockers.add("Provisioner identity cannot be established; set INVENTORY_PROVISIONER_ROLE to inspect it.");
                } else if (!roleExists(provisioner)) {
                    blockers.add("Configured provisioner role does not exist or is not verifiable.");
                    roleEvidenceUnknown |= hasUnverifiableAttributes(List.of(provisioner));
                } else {
                    if (hasUnverifiableAttributes(List.of(provisioner))) {
                        roleEvidenceUnknown = true;
                        blockers.add("One or more provisioner attributes could not be verified.");
                    }
                    if (hasUnsafeProvisionerAttributes(provisioner)) {
                        blockers.add("Provisioner role has security attributes outside the D1 safe inventory profile.");
                    }
                }
                report.put("effectiveGrants", Map.of(
                        "schemaAndDatabase", query(connection, "SELECT r.rolname AS role, "
                                + "has_schema_privilege(r.rolname, 'public', 'USAGE') AS public_usage, "
                                + "has_schema_privilege(r.rolname, 'public', 'CREATE') AS public_create, "
                                + "has_database_privilege(r.rolname, current_database(), 'CONNECT') AS database_connect, "
                                + "has_database_privilege(r.rolname, current_database(), 'CREATE') AS database_create, "
                                + "has_database_privilege(r.rolname, current_database(), 'TEMP') AS database_temp "
                                + "FROM pg_roles r WHERE r.rolname IN ('app_migrator','app_runtime','app_bootstrap') ORDER BY r.rolname"),
                        "tables", query(connection, "SELECT r.rolname AS role, wanted.table_name, "
                                + "CASE WHEN c.oid IS NULL THEN NULL ELSE has_table_privilege(r.rolname,c.oid,'SELECT') END AS can_select, "
                                + "CASE WHEN c.oid IS NULL THEN NULL ELSE has_table_privilege(r.rolname,c.oid,'INSERT') END AS can_insert, "
                                + "CASE WHEN c.oid IS NULL THEN NULL ELSE has_table_privilege(r.rolname,c.oid,'UPDATE') END AS can_update, "
                                + "CASE WHEN c.oid IS NULL THEN NULL ELSE has_table_privilege(r.rolname,c.oid,'DELETE') END AS can_delete, "
                                + "CASE WHEN c.oid IS NULL THEN NULL ELSE has_table_privilege(r.rolname,c.oid,'TRUNCATE') END AS can_truncate, "
                                + "CASE WHEN c.oid IS NULL THEN NULL ELSE has_table_privilege(r.rolname,c.oid,'REFERENCES') END AS can_references, "
                                + "CASE WHEN c.oid IS NULL THEN NULL ELSE has_table_privilege(r.rolname,c.oid,'TRIGGER') END AS can_trigger "
                                + "FROM pg_roles r CROSS JOIN (VALUES ('users'),('customers'),('products'),('orders'),"
                                + "('order_items'),('user_customer_link_audit')) wanted(table_name) "
                                + "LEFT JOIN pg_class c ON c.relnamespace='public'::regnamespace AND c.relname=wanted.table_name "
                                + "AND c.relkind IN ('r','p','v','m','f') "
                                + "WHERE r.rolname IN ('app_migrator','app_runtime','app_bootstrap') ORDER BY r.rolname,wanted.table_name"),
                        "sequences", query(connection, "SELECT r.rolname AS role, c.relname AS sequence, "
                                + "has_sequence_privilege(r.rolname,c.oid,'USAGE') AS can_use, "
                                + "has_sequence_privilege(r.rolname,c.oid,'SELECT') AS can_select, "
                                + "has_sequence_privilege(r.rolname,c.oid,'UPDATE') AS can_update "
                                + "FROM pg_roles r CROSS JOIN pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                                + "WHERE r.rolname IN ('app_migrator','app_runtime','app_bootstrap') "
                                + "AND n.nspname='public' AND c.relkind='S' ORDER BY r.rolname,c.relname")));

                List<Map<String, Object>> objects = query(connection, objectInventorySql());
                report.put("objects", objects);
                String owner = null;
                boolean ownershipUniform = true;
                List<String> unclassified = new ArrayList<>();
                for (Map<String, Object> object : objects) {
                    String objectOwner = String.valueOf(object.get("owner"));
                    if (owner == null) {
                        owner = objectOwner;
                    } else if (!owner.equals(objectOwner)) {
                        ownershipUniform = false;
                    }
                    if (Boolean.FALSE.equals(object.get("classified"))) {
                        unclassified.add(String.valueOf(object.get("identity")));
                    }
                }
                report.put("applicationOwner", owner);
                report.put("ownershipUniform", ownershipUniform);
                report.put("unclassifiedObjects", unclassified);
                if (!ownershipUniform) {
                    blockers.add("Application object ownership is mixed.");
                } else if (owner == null) {
                    blockers.add("Application object ownership cannot be established.");
                }
                if (!unclassified.isEmpty()) {
                    blockers.add("Unclassified objects or dependencies are present.");
                }
                List<Map<String, Object>> dependencies = dependencyInventory(connection);
                report.put("dependencies", dependencies);
                if (dependencies.stream().anyMatch(dependency -> !Boolean.TRUE.equals(dependency.get("classified")))) {
                    blockers.add("Dependencies outside the application schema or extension ownership are present.");
                }

                report.put("extensions", query(connection, "SELECT e.extname AS name, e.extversion AS version, "
                        + "n.nspname AS schema, pg_get_userbyid(e.extowner) AS owner "
                        + "FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace ORDER BY e.extname"));
                boolean pgcrypto = castList(report.get("extensions")).stream()
                        .anyMatch(extension -> "pgcrypto".equals(extension.get("name")));
                if (!pgcrypto) {
                    blockers.add("Required extension pgcrypto is absent.");
                }

                Map<String, Object> protections = protectionInventory(connection, migrationState);
                report.put("protections", protections);
                if (!Boolean.TRUE.equals(protections.get("match"))) {
                    blockers.add("V4/V5 protection functions or triggers are absent, disabled, or divergent.");
                }

                Map<String, Object> integrity = dataInventory(connection);
                report.put("data", integrity);
                if (!Boolean.TRUE.equals(integrity.get("allBusinessTablesPresent"))) {
                    blockers.add("One or more required application tables are absent or not verifiable.");
                }
                if (!Boolean.TRUE.equals(integrity.get("referentialConstraintsValidated"))) {
                    blockers.add("Referential integrity could not be verified.");
                }
                report.put("consumers", Map.of("status", "NOT_VERIFIABLE",
                        "operatorAction", "Review deployment configuration and active sessions without exposing credentials."));
                String backupEvidenceRef = System.getenv("INVENTORY_BACKUP_EVIDENCE_REF");
                report.put("backupEvidence", Map.of("status", backupEvidenceRef == null || backupEvidenceRef.isBlank()
                                ? "NOT_PROVIDED" : "OPERATOR_REFERENCE_PROVIDED",
                        "reference", backupEvidenceRef == null ? "" : backupEvidenceRef,
                        "restoreVerifiedByInventory", false,
                        "operatorAction", "Verify backup restoration operationally; this inventory does not validate restoration."));

                connection.rollback();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }

        report.put("blockers", blockers);
        String finalFlywayState = String.valueOf(castMap(report.get("flyway")).get("state"));
        if (List.of("ABSENT", "FAILED", "DIVERGENT").contains(finalFlywayState)) {
            report.put("classification", "REJECTED");
        } else if (blockers.isEmpty()) {
            report.put("classification", "SUPPORTED_" + String.valueOf(castMap(report.get("flyway")).get("state")));
        } else if (roleEvidenceUnknown || "UNKNOWN".equals(finalFlywayState)
                || "NOT_VERIFIABLE".equals(finalFlywayState)
                || blockers.stream().anyMatch(reason -> reason.contains("cannot be established"))) {
            report.put("classification", "UNKNOWN");
        } else {
            report.put("classification", "REJECTED");
        }
        report.put("classificationCaveat", "A supported database state is only a technical candidate. Operator approval, "
                + "restorable backup evidence, consumer review, and cutover checks remain mandatory.");
        return report;
    }

    private static Map<String, Object> flywayHistory(String url, String username, String password, Connection connection) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            boolean tableExists = Boolean.TRUE.equals(queryOne(connection, "SELECT to_regclass('public.flyway_schema_history') "
                    + "IS NOT NULL AS present").get("present"));
            if (!tableExists) {
                result.put("state", "ABSENT");
                result.put("entries", List.of());
                return result;
            }
            Flyway flyway = Flyway.configure().dataSource(readOnlyJdbcUrl(url), username, password)
                    .locations("classpath:db/migration").load();
            List<Map<String, Object>> entries = new ArrayList<>();
            Map<String, Integer> resolvedChecksums = expectedMigrationChecksums();
            Map<String, String> resolvedStates = new LinkedHashMap<>();
            for (MigrationInfo info : flyway.info().all()) {
                if (info.getVersion() == null) {
                    continue;
                }
                resolvedStates.put(info.getVersion().getVersion(), info.getState().name());
            }
            List<Map<String, Object>> installedRows = query(connection, "SELECT version, description, checksum, success, installed_by "
                    + "FROM public.flyway_schema_history ORDER BY installed_rank");
            for (Map<String, Object> installed : installedRows) {
                String version = String.valueOf(installed.get("version"));
                Map<String, Object> row = new LinkedHashMap<>();
                Integer installedChecksum = installed.get("checksum") == null ? null : ((Number) installed.get("checksum")).intValue();
                Integer resolvedChecksum = resolvedChecksums.get(version);
                String flywayState = resolvedStates.get(version);
                boolean success = Boolean.TRUE.equals(installed.get("success"));
                row.put("version", version);
                row.put("description", installed.get("description"));
                row.put("state", !success ? "FAILED" : resolvedChecksum == null ? "UNKNOWN" :
                        installedChecksum != null && !installedChecksum.equals(resolvedChecksum) ? "CHECKSUM_MISMATCH" : "SUCCESS");
                row.put("success", success);
                row.put("installedChecksum", installedChecksum);
                row.put("resolvedChecksum", resolvedChecksum);
                row.put("flywayState", flywayState);
                row.put("installedBy", installed.get("installed_by"));
                entries.add(row);
            }
            result.put("entries", entries);
            List<String> installed = entries.stream().filter(row -> row.get("installedChecksum") != null)
                    .map(row -> String.valueOf(row.get("version"))).toList();
            boolean failed = entries.stream().anyMatch(row -> Boolean.FALSE.equals(row.get("success")));
            boolean mismatch = entries.stream().anyMatch(row -> "UNKNOWN".equals(row.get("state"))
                    || "CHECKSUM_MISMATCH".equals(row.get("state"))
                    || !"SUCCESS".equals(row.get("flywayState")));
            if (failed) {
                result.put("state", "FAILED");
            } else if (mismatch) {
                result.put("state", "DIVERGENT");
            } else if (installed.equals(List.of("1", "2", "3", "4"))) {
                result.put("state", "V1_V4");
            } else if (installed.equals(List.of("1", "2", "3", "4", "5"))) {
                result.put("state", "V1_V5");
            } else {
                result.put("state", "UNKNOWN");
            }
        } catch (Exception exception) {
            result.put("state", "NOT_VERIFIABLE");
            result.put("errorType", exception.getClass().getSimpleName());
        }
        return result;
    }

    private static String readOnlyJdbcUrl(String url) {
        return url + (url.contains("?") ? "&" : "?") + "readOnly=true";
    }

    private static Map<String, Integer> expectedMigrationChecksums() throws Exception {
        Map<String, Integer> checksums = new LinkedHashMap<>();
        for (String migration : List.of(
                "V1__create_schema.sql", "V2__add_order_idempotency_key.sql", "V3__add_product_version.sql",
                "V4__add_user_customer_link_and_audit.sql", "V5__separate_runtime_user_privileges.sql")) {
            String version = migration.substring(1, migration.indexOf("__"));
            checksums.put(version, flywayChecksum("/db/migration/" + migration));
        }
        return checksums;
    }

    private static int flywayChecksum(String resourcePath) throws Exception {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (var stream = DatabaseInventoryCommand.class.getResourceAsStream(resourcePath);
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream,
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                crc.update(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return (int) crc.getValue();
    }

    private static List<Map<String, Object>> roleInventory(Connection connection) throws SQLException {
        return queryRoles(connection, APP_ROLES, "APPLICATION");
    }

    private static Map<String, Object> provisionerInventory(Connection connection, String roleName)
            throws SQLException {
        if (roleName == null || roleName.isBlank()) {
            return unverifiableProvisioner(null, "Provisioner role name was not configured; it was not inferred from the connection.");
        }
        if (APP_ROLES.contains(roleName)) {
            return unverifiableProvisioner(roleName, "Provisioner identity must be distinct from application roles.");
        }
        return queryRoles(connection, List.of(roleName), "PROVISIONER").getFirst();
    }

    private static List<Map<String, Object>> queryRoles(Connection connection, List<String> roleNames, String roleType)
            throws SQLException {
        String sql = "WITH wanted AS (SELECT name, ordinal FROM unnest(?::text[]) WITH ORDINALITY AS roles(name, ordinal)) "
                + "SELECT wanted.name, r.oid AS role_oid, r.rolcanlogin AS can_login, r.rolsuper AS superuser, "
                + "r.rolcreaterole AS create_role, r.rolcreatedb AS create_db, r.rolreplication AS replication, "
                + "r.rolbypassrls AS bypass_rls, r.rolinherit AS inherit, r.rolvaliduntil AS valid_until, "
                + "r.rolconfig AS role_config, "
                + "COALESCE((SELECT json_agg(json_build_object('role', parent.rolname, 'adminOption', m.admin_option, "
                + "'inheritOption', m.inherit_option, 'setOption', m.set_option)) FROM pg_auth_members m "
                + "JOIN pg_roles parent ON parent.oid=m.roleid WHERE m.member=r.oid), '[]'::json)::text AS memberships "
                + "FROM wanted LEFT JOIN pg_roles r ON r.rolname=wanted.name ORDER BY wanted.ordinal";
        java.sql.Array names = connection.createArrayOf("text", roleNames.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, names);
            try (ResultSet rows = statement.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(roleRow(rows, roleType));
                }
                return result;
            }
        } finally {
            names.free();
        }
    }

    private static Map<String, Object> roleRow(ResultSet row, String roleType) throws SQLException {
        String roleName = row.getString("name");
        boolean exists = row.getObject("role_oid") != null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roleType", roleType);
        result.put("roleName", CollectionEvidence.present(roleName));
        result.put("roleExists", CollectionEvidence.present(exists));

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("rolsuper", booleanAttribute(row, "superuser", exists));
        attributes.put("rolcreaterole", booleanAttribute(row, "create_role", exists));
        attributes.put("rolcreatedb", booleanAttribute(row, "create_db", exists));
        attributes.put("rolreplication", booleanAttribute(row, "replication", exists));
        attributes.put("rolbypassrls", booleanAttribute(row, "bypass_rls", exists));
        attributes.put("rolinherit", booleanAttribute(row, "inherit", exists));
        attributes.put("rolcanlogin", booleanAttribute(row, "can_login", exists));
        attributes.put("rolvaliduntil", validUntilAttribute(row, exists));
        attributes.put("rolconfig", roleConfigAttribute(row, exists));
        result.put("attributes", attributes);
        result.put("memberships", exists
                ? CollectionEvidence.present(row.getString("memberships"))
                : CollectionEvidence.notApplicable("Role does not exist."));
        return result;
    }

    private static CollectionEvidence<Boolean> booleanAttribute(ResultSet row, String column, boolean roleExists)
            throws SQLException {
        if (!roleExists) {
            return CollectionEvidence.notApplicable("Role does not exist.");
        }
        boolean value = row.getBoolean(column);
        return row.wasNull() ? CollectionEvidence.nullValue("PostgreSQL returned NULL for this role attribute.")
                : CollectionEvidence.present(value);
    }

    private static CollectionEvidence<String> validUntilAttribute(ResultSet row, boolean roleExists) throws SQLException {
        if (!roleExists) {
            return CollectionEvidence.notApplicable("Role does not exist.");
        }
        Timestamp value = row.getTimestamp("valid_until");
        return value == null ? CollectionEvidence.nullValue("Role has no password-authentication expiry configured.")
                : CollectionEvidence.present(value.toInstant().toString());
    }

    private static CollectionEvidence<List<String>> roleConfigAttribute(ResultSet row, boolean roleExists)
            throws SQLException {
        if (!roleExists) {
            return CollectionEvidence.notApplicable("Role does not exist.");
        }
        java.sql.Array settings = row.getArray("role_config");
        if (settings == null) {
            return CollectionEvidence.nullValue("Role has no role-level configuration settings.");
        }
        try {
            Object raw = settings.getArray();
            if (!(raw instanceof Object[] values)) {
                return CollectionEvidence.notVerifiable("Role settings were omitted because their representation was not recognized.");
            }
            if (values.length == 0) {
                return CollectionEvidence.present(List.of());
            }
            return CollectionEvidence.notVerifiable(
                    "Non-empty role settings are redacted; this inventory version does not evaluate their values.");
        } finally {
            settings.free();
        }
    }

    private static List<Map<String, Object>> unverifiableApplicationRoles(String reason) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : APP_ROLES) {
            result.add(unverifiableRole(name, "APPLICATION", reason));
        }
        return result;
    }

    private static Map<String, Object> unverifiableProvisioner(String roleName, String reason) {
        Map<String, Object> result = unverifiableRole(roleName, "PROVISIONER", reason);
        if (roleName == null || roleName.isBlank()) {
            result.put("roleName", CollectionEvidence.notVerifiable(reason));
        }
        return result;
    }

    private static Map<String, Object> unverifiableRole(String roleName, String roleType, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roleType", roleType);
        result.put("roleName", roleName == null
                ? CollectionEvidence.notVerifiable(reason) : CollectionEvidence.present(roleName));
        result.put("roleExists", CollectionEvidence.notVerifiable(reason));
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (String attribute : List.of("rolsuper", "rolcreaterole", "rolcreatedb", "rolreplication",
                "rolbypassrls", "rolinherit", "rolcanlogin", "rolvaliduntil", "rolconfig")) {
            attributes.put(attribute, CollectionEvidence.notVerifiable(reason));
        }
        result.put("attributes", attributes);
        result.put("memberships", CollectionEvidence.notVerifiable(reason));
        return result;
    }

    static boolean validTargetRoles(List<Map<String, Object>> roles) {
        return roles.stream().allMatch(role -> roleExists(role)
                && attributeEquals(role, "rolcanlogin", true)
                && attributeEquals(role, "rolsuper", false)
                && attributeEquals(role, "rolcreatedb", false)
                && attributeEquals(role, "rolcreaterole", false)
                && attributeEquals(role, "rolinherit", false)
                && attributeEquals(role, "rolreplication", false)
                && attributeEquals(role, "rolbypassrls", false)
                && nullOrPresentAttribute(role, "rolvaliduntil")
                && emptyOrNullConfig(role)
                && "[]".equals(evidenceValue(role.get("memberships"))));
    }

    private static boolean roleExists(Map<String, Object> role) {
        return evidenceEquals(role.get("roleExists"), CollectionState.PRESENT, true);
    }

    private static boolean attributeEquals(Map<String, Object> role, String name, Object expected) {
        return evidenceEquals(attribute(role, name), CollectionState.PRESENT, expected);
    }

    private static boolean nullOrPresentAttribute(Map<String, Object> role, String name) {
        CollectionState state = evidenceState(attribute(role, name));
        return state == CollectionState.NULL_VALUE || state == CollectionState.PRESENT;
    }

    private static boolean emptyOrNullConfig(Map<String, Object> role) {
        Object evidence = attribute(role, "rolconfig");
        return evidenceState(evidence) == CollectionState.NULL_VALUE
                || evidenceEquals(evidence, CollectionState.PRESENT, List.of());
    }

    private static boolean hasUnverifiableAttributes(List<Map<String, Object>> roles) {
        return roles.stream().anyMatch(role -> castMap(role.get("attributes")).values().stream()
                .anyMatch(value -> evidenceState(value) == CollectionState.NOT_VERIFIABLE
                        || evidenceState(value) == CollectionState.NOT_COLLECTED));
    }

    private static boolean hasUnsafeProvisionerAttributes(Map<String, Object> role) {
        return attributeEquals(role, "rolsuper", true)
                || attributeEquals(role, "rolcreatedb", true)
                || attributeEquals(role, "rolreplication", true)
                || attributeEquals(role, "rolbypassrls", true);
    }

    private static Object attribute(Map<String, Object> role, String name) {
        return castMap(role.get("attributes")).get(name);
    }

    private static boolean evidenceEquals(Object value, CollectionState state, Object expected) {
        return value instanceof CollectionEvidence<?> evidence && evidence.state() == state
                && Objects.equals(evidence.value(), expected);
    }

    private static CollectionState evidenceState(Object value) {
        return value instanceof CollectionEvidence<?> evidence ? evidence.state() : CollectionState.NOT_VERIFIABLE;
    }

    private static Object evidenceValue(Object value) {
        return value instanceof CollectionEvidence<?> evidence ? evidence.value() : null;
    }

    private static String objectInventorySql() {
        return "WITH objects AS ("
                + "SELECT 'table' AS kind, c.oid, n.nspname AS schema, c.relname AS name, "
                + "pg_get_userbyid(c.relowner) AS owner, c.relacl::text AS acl "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f') "
                + "AND c.relname <> 'flyway_schema_history' "
                + "UNION ALL SELECT 'index', c.oid, n.nspname, c.relname, pg_get_userbyid(c.relowner), c.relacl::text "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind='i' "
                + "UNION ALL SELECT CASE WHEN p.prokind='p' THEN 'procedure' ELSE 'function' END, p.oid, "
                + "n.nspname, p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')', "
                + "pg_get_userbyid(p.proowner), p.proacl::text FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                + "WHERE n.nspname='public' AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid='pg_proc'::regclass "
                + "AND d.objid=p.oid AND d.deptype='e') "
                + "UNION ALL SELECT 'trigger', t.oid, n.nspname, t.tgname, pg_get_userbyid(c.relowner), NULL::text "
                + "FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname='public' AND NOT t.tgisinternal "
                + "UNION ALL SELECT 'type', t.oid, n.nspname, t.typname, pg_get_userbyid(t.typowner), NULL::text "
                + "FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace "
                + "WHERE n.nspname='public' AND t.typtype IN ('e','d') AND NOT EXISTS "
                + "(SELECT 1 FROM pg_depend d WHERE d.classid='pg_type'::regclass AND d.objid=t.oid AND d.deptype='e')) "
                + "SELECT kind, schema, name, owner, acl, schema||'.'||kind||':'||name AS identity, "
                + "(name = ANY (ARRAY['users','customers','products','orders','order_items','user_customer_link_audit',"
                + "'idx_orders_customer_id','idx_orders_status','idx_order_items_order_id','idx_products_sku',"
                + "'users_pkey','users_email_key','customers_pkey','customers_email_key','customers_document_key',"
                + "'products_pkey','products_sku_key','orders_pkey','order_items_pkey','uk_orders_idempotency_key',"
                + "'user_customer_link_audit_pkey','flyway_schema_history_pk','flyway_schema_history_s_idx',"
                + "'idx_users_customer_id','prevent_user_customer_link_audit_mutation()',"
                + "'prevent_runtime_admin_user_write()','trg_user_customer_link_audit_append_only',"
                + "'trg_prevent_runtime_admin_user_write'])) AS classified FROM objects ORDER BY kind, name";
    }

    private static Map<String, Object> protectionInventory(Connection connection, String migrationState) throws SQLException {
        List<Map<String, Object>> rows = query(connection, "SELECT p.proname AS name, "
                + "pg_get_userbyid(p.proowner) AS owner, p.prosrc AS source, "
                + "COALESCE(p.proacl::text, 'DEFAULT') AS acl, "
                + "EXISTS (SELECT 1 FROM aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) a "
                + "WHERE a.grantee=0 AND a.privilege_type='EXECUTE') AS public_execute FROM pg_proc p "
                + "JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' "
                + "AND p.proname IN ('prevent_user_customer_link_audit_mutation','prevent_runtime_admin_user_write') ORDER BY p.proname");
        List<Map<String, Object>> triggers = query(connection, "SELECT t.tgname AS name, c.relname AS table, "
                + "t.tgenabled AS enabled, t.tgtype AS eventMask, pg_get_triggerdef(t.oid, true) AS definition "
                + "FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace "
                + "WHERE n.nspname='public' AND NOT t.tgisinternal ORDER BY t.tgname");
        String expectedV4 = expectedFunctionSource("V4__add_user_customer_link_and_audit.sql",
                "prevent_user_customer_link_audit_mutation");
        String expectedV5 = expectedFunctionSource("V5__separate_runtime_user_privileges.sql",
                "prevent_runtime_admin_user_write");
        boolean v4 = functionMatches(rows, "prevent_user_customer_link_audit_mutation", expectedV4)
                && triggerMatches(triggers, "trg_user_customer_link_audit_append_only", "user_customer_link_audit",
                "prevent_user_customer_link_audit_mutation", 27);
        boolean v5 = !"V1_V4".equals(migrationState)
                && functionMatches(rows, "prevent_runtime_admin_user_write", expectedV5)
                && rows.stream().anyMatch(row -> "prevent_runtime_admin_user_write".equals(row.get("name"))
                && Boolean.FALSE.equals(row.get("public_execute")))
                && triggerMatches(triggers, "trg_prevent_runtime_admin_user_write", "users",
                "prevent_runtime_admin_user_write", 23);
        return Map.of("functions", rows, "triggers", triggers, "v4PresentAndEnabled", v4,
                "v5PresentAndEnabled", v5, "match", v4 && ("V1_V4".equals(migrationState) || v5),
                "definitionComparison", "Function source is compared to versioned migration source; trigger event, table, function and enabled state are compared to versioned definitions. Owner and ACL are reported for review.");
    }

    private static List<Map<String, Object>> dependencyInventory(Connection connection) throws SQLException {
        return query(connection, "SELECT d.deptype AS dependencyType, "
                + "COALESCE(onsp.nspname, onp.nspname, ont.nspname, 'pg_catalog') AS dependentSchema, COALESCE(oc.relname, op.proname, ot.typname, '?') AS dependentObject, "
                + "COALESCE(rnsp.nspname, rnp.nspname, rnt.nspname, 'pg_catalog') AS referencedSchema, COALESCE(rc.relname, rp.proname, rt.typname, '?') AS referencedObject, "
                + "(COALESCE(rnsp.nspname, rnp.nspname, rnt.nspname, 'pg_catalog') IN ('pg_catalog','information_schema','public') "
                + "OR EXISTS (SELECT 1 FROM pg_depend ed WHERE ed.classid=d.refclassid AND ed.objid=d.refobjid AND ed.deptype='e')) AS classified "
                + "FROM pg_depend d "
                + "LEFT JOIN pg_class oc ON d.classid='pg_class'::regclass AND oc.oid=d.objid "
                + "LEFT JOIN pg_namespace onsp ON onsp.oid=oc.relnamespace "
                + "LEFT JOIN pg_proc op ON d.classid='pg_proc'::regclass AND op.oid=d.objid "
                + "LEFT JOIN pg_namespace onp ON onp.oid=op.pronamespace "
                + "LEFT JOIN pg_type ot ON d.classid='pg_type'::regclass AND ot.oid=d.objid "
                + "LEFT JOIN pg_namespace ont ON ont.oid=ot.typnamespace "
                + "LEFT JOIN pg_class rc ON d.refclassid='pg_class'::regclass AND rc.oid=d.refobjid "
                + "LEFT JOIN pg_namespace rnsp ON rnsp.oid=rc.relnamespace "
                + "LEFT JOIN pg_proc rp ON d.refclassid='pg_proc'::regclass AND rp.oid=d.refobjid "
                + "LEFT JOIN pg_namespace rnp ON rnp.oid=rp.pronamespace "
                + "LEFT JOIN pg_type rt ON d.refclassid='pg_type'::regclass AND rt.oid=d.refobjid "
                + "LEFT JOIN pg_namespace rnt ON rnt.oid=rt.typnamespace "
                + "WHERE (onsp.nspname='public' OR onp.nspname='public' OR ont.nspname='public') "
                + "AND d.deptype NOT IN ('i','e') ORDER BY dependentSchema, dependentObject, referencedSchema, referencedObject");
    }

    private static boolean functionMatches(List<Map<String, Object>> rows, String name, String expectedSource) {
        return rows.stream().anyMatch(row -> name.equals(row.get("name"))
                && normalize(String.valueOf(row.get("source"))).equals(normalize(expectedSource)));
    }

    private static boolean triggerMatches(List<Map<String, Object>> triggers, String name, String table,
            String functionName, int expectedEventMask) {
        return triggers.stream().anyMatch(row -> name.equals(row.get("name"))
                && table.equals(row.get("table")) && "O".equals(row.get("enabled"))
                && ((Number) row.get("eventmask")).intValue() == expectedEventMask
                && normalize(String.valueOf(row.get("definition")).replace("public.", "")).contains(normalize(functionName)));
    }

    private static String expectedFunctionSource(String migration, String functionName) {
        try (var stream = DatabaseInventoryCommand.class.getResourceAsStream("/db/migration/" + migration)) {
            if (stream == null) {
                return "";
            }
            String sql = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int functionStart = sql.toLowerCase().indexOf("create function " + functionName.toLowerCase());
            if (functionStart < 0) {
                return "";
            }
            int delimiterStart = sql.indexOf("$$", functionStart);
            int bodyEnd = delimiterStart < 0 ? -1 : sql.indexOf("$$", delimiterStart + 2);
            return bodyEnd < 0 ? "" : sql.substring(delimiterStart + 2, bodyEnd);
        } catch (Exception exception) {
            return "";
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static Map<String, Object> dataInventory(Connection connection) throws SQLException {
        Map<String, Object> counts = new LinkedHashMap<>();
        boolean allTablesPresent = true;
        for (String table : BUSINESS_TABLES) {
            try {
                boolean exists = Boolean.TRUE.equals(queryOne(connection,
                        "SELECT to_regclass('public.\"" + table + "\"') IS NOT NULL AS present").get("present"));
                if (!exists) {
                    allTablesPresent = false;
                    counts.put(table, Map.of("status", "NOT_VERIFIABLE", "reason", "table absent"));
                } else {
                    counts.put(table, queryOne(connection, "SELECT count(*) AS row_count FROM public.\""
                            + table + "\"").get("row_count"));
                }
            } catch (SQLException exception) {
                allTablesPresent = false;
                counts.put(table, Map.of("status", "NOT_VERIFIABLE", "reason", exception.getSQLState()));
            }
        }
        List<Map<String, Object>> constraints = query(connection, "SELECT conname AS name, conrelid::regclass::text AS table, "
                + "contype AS type, convalidated AS validated, pg_get_constraintdef(oid, true) AS definition "
                + "FROM pg_constraint WHERE connamespace='public'::regnamespace ORDER BY conrelid::regclass::text, conname");
        boolean validated = constraints.stream().allMatch(row -> Boolean.TRUE.equals(row.get("validated")));
        return Map.of("rowCounts", counts, "constraints", constraints,
                "allBusinessTablesPresent", allTablesPresent,
                "referentialConstraintsValidated", validated,
                "snapshot", "Counts collected in one REPEATABLE READ, READ ONLY transaction; no personal rows are copied.");
    }

    private static List<Map<String, Object>> query(Connection connection, String sql) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            int columns = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columns; i++) {
                    row.put(resultSet.getMetaData().getColumnLabel(i), jsonSafe(resultSet.getObject(i)));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static Object jsonSafe(Object value) throws SQLException {
        if (value instanceof java.sql.Array array) {
            Object raw = array.getArray();
            array.free();
            if (raw instanceof Object[] values) {
                return java.util.Arrays.stream(values).map(item -> {
                    try {
                        return jsonSafe(item);
                    } catch (SQLException exception) {
                        return "NOT_VERIFIABLE";
                    }
                }).toList();
            }
            return raw;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof java.util.Date) {
            return value;
        }
        if (value instanceof java.util.UUID) {
            return value.toString();
        }
        return value.toString();
    }

    private static Map<String, Object> queryOne(Connection connection, String sql) throws SQLException {
        List<Map<String, Object>> rows = query(connection, sql);
        if (rows.size() != 1) {
            throw new SQLException("Expected one inventory result row.");
        }
        return rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + name);
        }
        return value;
    }

    private static String optional(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
}
