package com.orion.database.migration;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Multi-database Flyway configuration for the Orion platform.
 *
 * <p>WHY: The original TypeScript story's config.js supported three databases (orion, rfq,
 * marketdata). Spring Boot's default Flyway auto-configuration only handles a single datasource. We
 * disable it and create separate Flyway beans — one per database — each configured with its own
 * JDBC URL, credentials, and migration file locations.
 *
 * <h2>Bean Names</h2>
 *
 * <ul>
 *   <li>{@link #ORION_FLYWAY_BEAN} — main Orion database (always active)
 *   <li>{@link #RFQ_FLYWAY_BEAN} — RFQ service database (activated by config)
 *   <li>{@link #MARKETDATA_FLYWAY_BEAN} — Market data database (activated by config)
 * </ul>
 *
 * <h2>Excluding Spring Boot Auto-Configuration</h2>
 *
 * <p>Services using this module should exclude {@link FlywayAutoConfiguration} to prevent
 * conflicts:
 *
 * <pre>{@code
 * @SpringBootApplication(exclude = FlywayAutoConfiguration.class)
 * }</pre>
 *
 * <p>Or in application.yml:
 *
 * <pre>{@code
 * spring:
 *   flyway:
 *     enabled: false
 * }</pre>
 *
 * @see FlywayConfigProperties
 */
@Configuration
@EnableConfigurationProperties(FlywayConfigProperties.class)
@ConditionalOnProperty(prefix = "orion.flyway.orion", name = "enabled", havingValue = "true")
public class FlywayMultiDatabaseConfig {

    /** Bean name for the main Orion database Flyway instance. */
    public static final String ORION_FLYWAY_BEAN = "orionFlyway";

    /** Bean name for the RFQ service database Flyway instance. */
    public static final String RFQ_FLYWAY_BEAN = "rfqFlyway";

    /** Bean name for the Market Data service database Flyway instance. */
    public static final String MARKETDATA_FLYWAY_BEAN = "marketdataFlyway";

    /**
     * Creates and configures a Flyway instance for the main Orion database.
     *
     * <p>WHY: The main database holds tenants, users, audit logs, outbox events, and processed
     * events — the foundational tables that every service depends on.
     *
     * @param properties externalized multi-database Flyway configuration
     * @return configured Flyway instance (migration runs automatically via {@code Flyway#migrate})
     */
    @Bean(name = ORION_FLYWAY_BEAN)
    public Flyway orionFlyway(FlywayConfigProperties properties) {
        return createFlyway(properties.orion());
    }

    /**
     * Creates a Flyway instance for the RFQ service database.
     *
     * <p>Only created when {@code orion.flyway.rfq.enabled=true}.
     *
     * @param properties externalized configuration
     * @return configured Flyway instance
     */
    @Bean(name = RFQ_FLYWAY_BEAN)
    @ConditionalOnProperty(prefix = "orion.flyway.rfq", name = "enabled", havingValue = "true")
    public Flyway rfqFlyway(FlywayConfigProperties properties) {
        return createFlyway(properties.rfq());
    }

    /**
     * Creates a Flyway instance for the Market Data service database.
     *
     * <p>Only created when {@code orion.flyway.marketdata.enabled=true}.
     *
     * @param properties externalized configuration
     * @return configured Flyway instance
     */
    @Bean(name = MARKETDATA_FLYWAY_BEAN)
    @ConditionalOnProperty(
            prefix = "orion.flyway.marketdata",
            name = "enabled",
            havingValue = "true")
    public Flyway marketdataFlyway(FlywayConfigProperties properties) {
        return createFlyway(properties.marketdata());
    }

    // ── Private Helpers ──

    /**
     * Creates a configured Flyway instance from a {@link FlywayConfigProperties.DatabaseConfig}.
     *
     * <p>WHY: Each database gets its own DataSource and Flyway instance so migrations are isolated.
     * This prevents a schema change in one service from affecting another.
     */
    private Flyway createFlyway(FlywayConfigProperties.DatabaseConfig config) {
        DataSource dataSource =
                DataSourceBuilder.create()
                        .url(config.url())
                        .username(config.username())
                        .password(config.password())
                        .build();

        return Flyway.configure()
                .dataSource(dataSource)
                .locations(config.locations())
                .baselineOnMigrate(true)
                .cleanDisabled(true)
                .load();
    }
}
