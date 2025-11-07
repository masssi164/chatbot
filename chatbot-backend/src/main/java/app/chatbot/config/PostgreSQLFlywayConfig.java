package app.chatbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * PostgreSQL 16.x spezifische Konfiguration für Flyway
 * 
 * Diese Konfiguration behandelt PostgreSQL 16.x Kompatibilitätsprobleme
 * durch alternative Strategien falls Standard-Flyway Probleme auftreten.
 */
@Configuration
@Profile("prod")
public class PostgreSQLFlywayConfig {
    
    private static final Logger log = LoggerFactory.getLogger(PostgreSQLFlywayConfig.class);

    /**
     * Standard Flyway Strategie für PostgreSQL 16.x
     * Nutzt die aktuellste kompatible Flyway Version
     */
    @Bean
    @ConditionalOnProperty(name = "app.flyway.strategy", havingValue = "migrate", matchIfMissing = true)
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            // Validierung vor Migration für PostgreSQL 16.x
            flyway.info();
            
            // Baseline falls nötig (für existierende DB)
            if (flyway.info().current() == null) {
                flyway.baseline();
            }
            
            // Standard Migration
            flyway.migrate();
        };
    }

    /**
     * Fallback-Strategie: Flyway deaktivieren falls Probleme auftreten
     * Aktivierung über Property: app.flyway.strategy=skip
     */
    @Bean
    @ConditionalOnProperty(name = "app.flyway.strategy", havingValue = "skip")
    public FlywayMigrationStrategy skipFlywayStrategy() {
        return flyway -> {
            // No migration - for manual DB setup or when Flyway causes issues
            log.info("Flyway Migration skipped - Manual DB management active");
        };
    }
}