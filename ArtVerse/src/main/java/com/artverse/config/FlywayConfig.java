package com.artverse.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class FlywayConfig {

    private final ArtVerseProperties properties;

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            try {
                flyway.migrate();
            } catch (FlywayValidateException ex) {
                if (!shouldAutoRepair(ex)) {
                    throw ex;
                }
                log.warn("Flyway validation failed due to checksum mismatch; repairing schema history before retrying migration.");
                repair(flyway);
                flyway.migrate();
            }
        };
    }

    private boolean shouldAutoRepair(FlywayValidateException ex) {
        if (!properties.getFlyway().isAutoRepairChecksumMismatch()) {
            return false;
        }
        String message = ex.getMessage();
        return message != null && message.contains("checksum mismatch");
    }

    private void repair(Flyway flyway) {
        FluentConfiguration configuration = Flyway.configure()
                .configuration(flyway.getConfiguration());
        Flyway repairFlyway = configuration.load();
        repairFlyway.repair();
    }
}
