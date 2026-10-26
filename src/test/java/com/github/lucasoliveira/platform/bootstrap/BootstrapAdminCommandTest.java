package com.github.lucasoliveira.platform.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BootstrapAdminCommandTest {
    @Test
    void returnsDocumentedCodesForCreatedNoOpConflictAndInvalidInvocation() {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        try (var out = new PrintStream(output); var err = new PrintStream(errors)) {
            assertEquals(0, BootstrapAdminCommand.exitCode(BootstrapAdminService.Result.CREATED, out, err));
            assertEquals(0, BootstrapAdminCommand.exitCode(BootstrapAdminService.Result.NO_OP, out, err));
            assertEquals(3, BootstrapAdminCommand.exitCode(BootstrapAdminService.Result.CONFLICT, out, err));
            assertEquals(2, BootstrapAdminCommand.run(Map.of(), null, out, err));
            assertEquals(2, BootstrapAdminCommand.run(Map.of("BOOTSTRAP_DB_URL", "jdbc:test"), null, out, err));
        }
    }
}
