package com.orion.database.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FlywayConfigProperties} — the externalized configuration record that drives
 * Flyway multi-database migration behaviour.
 *
 * <p>WHY: The original TypeScript story used a config.js object with per-database URLs and
 * migration directories. In Java we use a {@code @ConfigurationProperties} record validated by Bean
 * Validation. These tests verify the record's construction and defaults.
 */
@DisplayName("FlywayConfigProperties")
class FlywayConfigPropertiesTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("creates with all required fields")
        void createsWithAllFields() {
            var props =
                    new FlywayConfigProperties(
                            new FlywayConfigProperties.DatabaseConfig(
                                    "jdbc:postgresql://localhost:5432/orion",
                                    "orion",
                                    "password",
                                    "classpath:db/migration/orion",
                                    true),
                            new FlywayConfigProperties.DatabaseConfig(
                                    "jdbc:postgresql://localhost:5432/orion_rfq",
                                    "orion",
                                    "password",
                                    "classpath:db/migration/rfq",
                                    false),
                            new FlywayConfigProperties.DatabaseConfig(
                                    "jdbc:postgresql://localhost:5432/orion_marketdata",
                                    "orion",
                                    "password",
                                    "classpath:db/migration/marketdata",
                                    false));

            assertThat(props.orion()).isNotNull();
            assertThat(props.orion().url()).isEqualTo("jdbc:postgresql://localhost:5432/orion");
            assertThat(props.rfq()).isNotNull();
            assertThat(props.marketdata()).isNotNull();
        }

        @Test
        @DisplayName("DatabaseConfig record exposes all fields")
        void databaseConfigFieldsAccessible() {
            var config =
                    new FlywayConfigProperties.DatabaseConfig(
                            "jdbc:postgresql://localhost:5432/orion",
                            "user",
                            "pass",
                            "classpath:db/migration/orion",
                            true);

            assertThat(config.url()).isEqualTo("jdbc:postgresql://localhost:5432/orion");
            assertThat(config.username()).isEqualTo("user");
            assertThat(config.password()).isEqualTo("pass");
            assertThat(config.locations()).isEqualTo("classpath:db/migration/orion");
            assertThat(config.enabled()).isTrue();
        }

        @Test
        @DisplayName("disabled DatabaseConfig is recognized")
        void disabledConfig() {
            var config =
                    new FlywayConfigProperties.DatabaseConfig(
                            "jdbc:postgresql://localhost:5432/orion_rfq",
                            "user",
                            "pass",
                            "classpath:db/migration/rfq",
                            false);

            assertThat(config.enabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Defaults and validation semantics")
    class Validation {

        @Test
        @DisplayName("null URL is accepted at record level (validated by Spring)")
        void nullUrlAccepted() {
            // Bean Validation runs at binding time, not record construction.
            // At the record level, null is technically allowed — Spring @Validated
            // catches this before the bean is wired.
            var config =
                    new FlywayConfigProperties.DatabaseConfig(
                            null, "user", "pass", "classpath:db/migration/orion", true);
            assertThat(config.url()).isNull();
        }

        @Test
        @DisplayName("orion database is the primary (always configured)")
        void orionIsPrimary() {
            var props =
                    new FlywayConfigProperties(
                            new FlywayConfigProperties.DatabaseConfig(
                                    "jdbc:postgresql://localhost:5432/orion",
                                    "orion",
                                    "pass",
                                    "classpath:db/migration/orion",
                                    true),
                            null,
                            null);

            assertThat(props.orion()).isNotNull();
            assertThat(props.orion().enabled()).isTrue();
            assertThat(props.rfq()).isNull();
            assertThat(props.marketdata()).isNull();
        }
    }
}
