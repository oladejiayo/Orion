package com.orion.database.migration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized Flyway configuration for multi-database migration support.
 *
 * <p>WHY: The original TypeScript story used a {@code config.js} object with per-database URLs and
 * migration directories. In Java, we use a {@code @ConfigurationProperties} record that Spring Boot
 * binds from {@code application.yml}. Bean Validation ({@code @Validated}) ensures required fields
 * are present at startup — failing fast rather than at migration time.
 *
 * <h2>Configuration Example</h2>
 *
 * <pre>{@code
 * orion:
 *   flyway:
 *     orion:
 *       url: jdbc:postgresql://localhost:5432/orion
 *       username: orion
 *       password: orion_dev_password
 *       locations: classpath:db/migration/orion
 *       enabled: true
 *     rfq:
 *       url: jdbc:postgresql://localhost:5432/orion_rfq
 *       username: orion
 *       password: orion_dev_password
 *       locations: classpath:db/migration/rfq
 *       enabled: false
 * }</pre>
 *
 * @param orion Main Orion database configuration (always enabled)
 * @param rfq RFQ service database configuration (enabled per-service)
 * @param marketdata Market data service database configuration (enabled per-service)
 */
@Validated
@ConfigurationProperties(prefix = "orion.flyway")
public record FlywayConfigProperties(
        @NotNull @Valid DatabaseConfig orion, DatabaseConfig rfq, DatabaseConfig marketdata) {

    /**
     * Configuration for a single database's Flyway instance.
     *
     * @param url JDBC connection URL (e.g., {@code jdbc:postgresql://localhost:5432/orion})
     * @param username Database username
     * @param password Database password
     * @param locations Flyway migration locations (e.g., {@code classpath:db/migration/orion})
     * @param enabled Whether to run migrations for this database on startup
     */
    public record DatabaseConfig(
            @NotBlank String url,
            @NotBlank String username,
            String password,
            @NotBlank String locations,
            boolean enabled) {}
}
