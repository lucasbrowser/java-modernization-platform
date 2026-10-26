package com.github.lucasoliveira.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JavaModernizationPlatformApplication {
    public static void main(String[] args) {
        if (args.length == 1 && "--migrate-only".equals(args[0])) {
            DatabaseMigrationJob.runFromEnvironment();
            return;
        }
        SpringApplication.run(JavaModernizationPlatformApplication.class, args);
    }
}
