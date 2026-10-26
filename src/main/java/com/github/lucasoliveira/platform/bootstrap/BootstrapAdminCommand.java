package com.github.lucasoliveira.platform.bootstrap;

import java.io.Console;
import java.io.PrintStream;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class BootstrapAdminCommand {
    static final int SUCCESS = 0;
    static final int INVALID = 2;
    static final int CONFLICT = 3;
    static final int FAILURE = 1;

    private BootstrapAdminCommand() { }

    public static int runFromEnvironment() {
        return run(System.getenv(), System.console(), System.out, System.err);
    }

    static int run(java.util.Map<String, String> environment, Console console, PrintStream out, PrintStream err) {
        if (console == null) {
            err.println("Bootstrap requires an interactive terminal.");
            return INVALID;
        }
        String url = environment.get("BOOTSTRAP_DB_URL");
        String password = environment.get("BOOTSTRAP_DB_PASSWORD");
        if (url == null || url.isBlank() || password == null || password.isBlank()) {
            err.println("Bootstrap database configuration is incomplete.");
            return INVALID;
        }
        String name = console.readLine("Administrator name: ");
        String email = console.readLine("Administrator email: ");
        char[] enteredPassword = console.readPassword("Administrator password: ");
        if (name == null || email == null || enteredPassword == null) {
            clear(enteredPassword);
            err.println("Bootstrap input is invalid.");
            return INVALID;
        }
        String cleartext = new String(enteredPassword);
        try {
            BootstrapAdminService service = new BootstrapAdminService(url, "app_bootstrap", password,
                    new BCryptPasswordEncoder());
            BootstrapAdminService.Result result = service.createFirstAdmin(name, email, cleartext);
            return exitCode(result, out, err);
        } catch (IllegalArgumentException invalid) {
            err.println("Bootstrap input is invalid.");
            return INVALID;
        } catch (Exception failure) {
            err.println("Bootstrap failed due to an operational error.");
            return FAILURE;
        } finally {
            clear(enteredPassword);
        }
    }

    static int exitCode(BootstrapAdminService.Result result, PrintStream out, PrintStream err) {
        return switch (result) {
            case CREATED -> { out.println("Administrator created."); yield SUCCESS; }
            case NO_OP -> { out.println("Administrator already exists; no changes made."); yield SUCCESS; }
            case CONFLICT -> { err.println("Bootstrap conflict; no changes made."); yield CONFLICT; }
        };
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }
}
