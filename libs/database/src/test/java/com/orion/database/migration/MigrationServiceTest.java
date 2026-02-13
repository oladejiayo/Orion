package com.orion.database.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MigrationService} — a lightweight helper that provides migration status
 * information and utility operations for the Orion platform.
 *
 * <p>WHY: The original story specified `npm run db:migrate:status` to show migration state. In
 * Java, we expose this through a service bean that wraps Flyway operations and can be used by
 * actuator endpoints or CLI commands.
 */
@DisplayName("MigrationService")
class MigrationServiceTest {

    @Nested
    @DisplayName("MigrationInfo record")
    class MigrationInfoRecord {

        @Test
        @DisplayName("creates with all fields")
        void createsWithAllFields() {
            var info =
                    new MigrationService.MigrationInfo(
                            "orion", "1", "initial_schema", "SUCCESS", "2024-01-01T00:00:00Z");

            assertThat(info.database()).isEqualTo("orion");
            assertThat(info.version()).isEqualTo("1");
            assertThat(info.description()).isEqualTo("initial_schema");
            assertThat(info.state()).isEqualTo("SUCCESS");
            assertThat(info.installedOn()).isEqualTo("2024-01-01T00:00:00Z");
        }

        @Test
        @DisplayName("equals and hashCode work for records")
        void equalsAndHashCode() {
            var info1 =
                    new MigrationService.MigrationInfo(
                            "orion", "1", "initial_schema", "SUCCESS", "2024-01-01T00:00:00Z");
            var info2 =
                    new MigrationService.MigrationInfo(
                            "orion", "1", "initial_schema", "SUCCESS", "2024-01-01T00:00:00Z");

            assertThat(info1).isEqualTo(info2);
            assertThat(info1.hashCode()).isEqualTo(info2.hashCode());
        }

        @Test
        @DisplayName("different database names produce inequality")
        void differentDatabaseNotEqual() {
            var info1 =
                    new MigrationService.MigrationInfo(
                            "orion", "1", "initial_schema", "SUCCESS", "2024-01-01T00:00:00Z");
            var info2 =
                    new MigrationService.MigrationInfo(
                            "rfq", "1", "initial_schema", "SUCCESS", "2024-01-01T00:00:00Z");

            assertThat(info1).isNotEqualTo(info2);
        }
    }

    @Nested
    @DisplayName("DatabaseStatus record")
    class DatabaseStatusRecord {

        @Test
        @DisplayName("creates with all fields")
        void createsWithAllFields() {
            var status =
                    new MigrationService.DatabaseStatus(
                            "orion", "jdbc:postgresql://localhost:5432/orion", 5, 2, "1");

            assertThat(status.database()).isEqualTo("orion");
            assertThat(status.url()).isEqualTo("jdbc:postgresql://localhost:5432/orion");
            assertThat(status.appliedMigrations()).isEqualTo(5);
            assertThat(status.pendingMigrations()).isEqualTo(2);
            assertThat(status.currentVersion()).isEqualTo("1");
        }

        @Test
        @DisplayName("zero migrations is valid initial state")
        void zeroMigrationsValid() {
            var status =
                    new MigrationService.DatabaseStatus(
                            "orion", "jdbc:postgresql://localhost:5432/orion", 0, 1, null);

            assertThat(status.appliedMigrations()).isZero();
            assertThat(status.currentVersion()).isNull();
        }
    }
}
