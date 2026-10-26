package com.github.lucasoliveira.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.github.lucasoliveira.platform.bootstrap.BootstrapAdminCommand;

@SpringBootApplication
public class JavaModernizationPlatformApplication {
    public static void main(String[] args) {
        if (args.length == 1 && "--migrate-only".equals(args[0])) {
            DatabaseMigrationJob.runFromEnvironment();
            return;
        }
        if (args.length == 1 && "--bootstrap-admin".equals(args[0])) {
            System.exit(BootstrapAdminCommand.runFromEnvironment());
        }
        if (java.util.Arrays.asList(args).contains("--bootstrap-admin")) {
            System.exit(2);
        }
        SpringApplication.run(JavaModernizationPlatformApplication.class, args);
    }
}
