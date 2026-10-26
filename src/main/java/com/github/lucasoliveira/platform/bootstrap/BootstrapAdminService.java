package com.github.lucasoliveira.platform.bootstrap;

import com.github.lucasoliveira.platform.auth.dto.RegisterRequest;
import com.github.lucasoliveira.platform.auth.entity.Role;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;

/** One-shot, database-only implementation of the explicit first-admin operation. */
public final class BootstrapAdminService {
    private static final long LOCK_KEY = 6_006_002L;

    public enum Result { CREATED, NO_OP, CONFLICT }

    private final String url;
    private final String username;
    private final String databasePassword;
    private final PasswordEncoder passwordEncoder;

    public BootstrapAdminService(String url, String username, String databasePassword,
            PasswordEncoder passwordEncoder) {
        this.url = url;
        this.username = username;
        this.databasePassword = databasePassword;
        this.passwordEncoder = passwordEncoder;
    }

    public Result createFirstAdmin(String name, String email, String password) throws SQLException {
        validate(name, email, password);
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        try (Connection connection = DriverManager.getConnection(url, username, databasePassword)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, LOCK_KEY);
                    lock.execute();
                }
                Result result = inspectAndCreate(connection, name, normalizedEmail, password);
                connection.commit();
                return result;
            } catch (Exception failure) {
                connection.rollback();
                if (failure instanceof SQLException sqlException) throw sqlException;
                if (failure instanceof RuntimeException runtimeException) throw runtimeException;
                throw new SQLException("Bootstrap operation failed", failure);
            }
        }
    }

    private Result inspectAndCreate(Connection connection, String name, String email, String password)
            throws SQLException {
        try (PreparedStatement existing = connection.prepareStatement(
                "SELECT role FROM users WHERE email = ? OR lower(email) = lower(?)")) {
            existing.setString(1, email);
            existing.setString(2, email);
            try (ResultSet rows = existing.executeQuery()) {
                boolean foundAdmin = false;
                boolean foundUser = false;
                while (rows.next()) {
                    if (Role.ADMIN.name().equals(rows.getString("role"))) foundAdmin = true;
                    else foundUser = true;
                }
                if (foundAdmin || foundUser) {
                    return foundAdmin && !foundUser ? Result.NO_OP : Result.CONFLICT;
                }
            }
        }
        try (PreparedStatement admin = connection.prepareStatement("SELECT 1 FROM users WHERE role = 'ADMIN' LIMIT 1")) {
            try (ResultSet rows = admin.executeQuery()) {
                if (rows.next()) return Result.CONFLICT;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO users (id, name, email, password, role) VALUES (?, ?, ?, ?, 'ADMIN')")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, name);
            insert.setString(3, email);
            insert.setString(4, passwordEncoder.encode(password));
            insert.executeUpdate();
        }
        return Result.CREATED;
    }

    private static void validate(String name, String email, String password) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            if (!validator.validate(new RegisterRequest(name, email, password)).isEmpty()) {
                throw new IllegalArgumentException("Bootstrap input is invalid");
            }
        }
    }
}
