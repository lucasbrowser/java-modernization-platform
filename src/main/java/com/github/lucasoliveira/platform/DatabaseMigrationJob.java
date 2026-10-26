package com.github.lucasoliveira.platform;

import org.flywaydb.core.Flyway;

/** One-shot Flyway entry point, run separately from the API process. */
public final class DatabaseMigrationJob {

    private DatabaseMigrationJob() {
    }

    static void runFromEnvironment() {
        String url = requiredEnvironmentVariable("MIGRATION_DB_URL");
        String username = requiredEnvironmentVariable("MIGRATION_DB_USERNAME");
        String password = requiredEnvironmentVariable("MIGRATION_DB_PASSWORD");
        migrate(url, username, password);
    }

    static void migrate(String url, String username, String password) {
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing: " + name);
        }
        return value;
    }
}
