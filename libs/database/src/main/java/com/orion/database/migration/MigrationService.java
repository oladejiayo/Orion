package com.orion.database.migration;

import java.util.List;

/**
 * Service providing migration status information and utility operations.
 *
 * <p>WHY: The original story specified {@code npm run db:migrate:status} to show migration state.
 * In Java, this service wraps Flyway operations and exposes status through records that can be used
 * by actuator endpoints, CLI commands, or health checks.
 *
 * <p>This is a POJO (no Spring annotations) — it can be instantiated in unit tests without a Spring
 * context. The Spring wiring happens in {@link FlywayMultiDatabaseConfig} or service-level config.
 */
public class MigrationService {

    /**
     * Represents the status of a single applied migration.
     *
     * @param database database name (e.g., "orion", "rfq")
     * @param version migration version (e.g., "1", "2")
     * @param description migration description (e.g., "initial_schema")
     * @param state migration state (e.g., "SUCCESS", "PENDING", "FAILED")
     * @param installedOn ISO-8601 timestamp of when the migration was applied
     */
    public record MigrationInfo(
            String database,
            String version,
            String description,
            String state,
            String installedOn) {}

    /**
     * Represents the overall status of a database's migrations.
     *
     * @param database database name (e.g., "orion")
     * @param url JDBC connection URL
     * @param appliedMigrations number of successfully applied migrations
     * @param pendingMigrations number of migrations waiting to be applied
     * @param currentVersion current schema version (null if no migrations applied)
     */
    public record DatabaseStatus(
            String database,
            String url,
            int appliedMigrations,
            int pendingMigrations,
            String currentVersion) {}

    private final List<DatabaseStatus> statuses;

    /**
     * Creates a MigrationService with pre-computed database statuses.
     *
     * @param statuses list of database status records
     */
    public MigrationService(List<DatabaseStatus> statuses) {
        this.statuses = List.copyOf(statuses);
    }

    /**
     * Returns the migration status for all configured databases.
     *
     * @return immutable list of database statuses
     */
    public List<DatabaseStatus> getAllStatuses() {
        return statuses;
    }

    /**
     * Returns the migration status for a specific database.
     *
     * @param database database name to look up
     * @return the status, or null if not found
     */
    public DatabaseStatus getStatus(String database) {
        return statuses.stream()
                .filter(s -> s.database().equals(database))
                .findFirst()
                .orElse(null);
    }
}
