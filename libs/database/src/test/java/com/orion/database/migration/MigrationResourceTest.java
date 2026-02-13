package com.orion.database.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests that verify the SQL migration files are valid and loadable from the classpath.
 *
 * <p>WHY: Migration SQL files must be on the classpath so Flyway can discover them at runtime.
 * These tests ensure the resource packaging is correct — a structural issue that would only surface
 * at deployment time without explicit verification.
 */
@DisplayName("Migration SQL Resource Verification")
class MigrationResourceTest {

    @Nested
    @DisplayName("Orion (main database) migrations")
    class OrionMigrations {

        @Test
        @DisplayName("V1__initial_schema.sql is on the classpath")
        void initialSchemaOnClasspath() {
            try (InputStream is =
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream("db/migration/orion/V1__initial_schema.sql")) {
                assertThat(is).as("V1__initial_schema.sql must be on the classpath").isNotNull();
            } catch (IOException e) {
                throw new AssertionError("Failed to read migration resource", e);
            }
        }

        @Test
        @DisplayName("V1__initial_schema.sql contains valid SQL statements")
        void initialSchemaContainsValidSql() throws IOException {
            String sql = readClasspathResource("db/migration/orion/V1__initial_schema.sql");

            assertThat(sql).isNotBlank();
            assertThat(sql).containsIgnoringCase("CREATE TABLE");
            // Must not contain TypeScript/JavaScript artifacts
            assertThat(sql).doesNotContain("module.exports");
            assertThat(sql).doesNotContain("require(");
        }

        @Test
        @DisplayName("V2__initial_triggers.sql is on the classpath")
        void triggersOnClasspath() {
            try (InputStream is =
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream("db/migration/orion/V2__initial_triggers.sql")) {
                assertThat(is).as("V2__initial_triggers.sql must be on the classpath").isNotNull();
            } catch (IOException e) {
                throw new AssertionError("Failed to read migration resource", e);
            }
        }

        @Test
        @DisplayName("V2__initial_triggers.sql creates trigger function")
        void triggersContainFunction() throws IOException {
            String sql = readClasspathResource("db/migration/orion/V2__initial_triggers.sql");

            assertThat(sql)
                    .as("Must create update_updated_at trigger function")
                    .containsIgnoringCase("CREATE OR REPLACE FUNCTION update_updated_at");
            assertThat(sql).as("Must create triggers").containsIgnoringCase("CREATE TRIGGER");
        }
    }

    @Nested
    @DisplayName("Seed data resources")
    class SeedDataResources {

        @Test
        @DisplayName("Development tenant seed SQL is on the classpath")
        void tenantSeedOnClasspath() {
            try (InputStream is =
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream("db/seed/development/V1000__seed_tenants.sql")) {
                assertThat(is).as("V1000__seed_tenants.sql must be on the classpath").isNotNull();
            } catch (IOException e) {
                throw new AssertionError("Failed to read seed resource", e);
            }
        }

        @Test
        @DisplayName("Development user seed SQL is on the classpath")
        void userSeedOnClasspath() {
            try (InputStream is =
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream("db/seed/development/V1001__seed_users.sql")) {
                assertThat(is).as("V1001__seed_users.sql must be on the classpath").isNotNull();
            } catch (IOException e) {
                throw new AssertionError("Failed to read seed resource", e);
            }
        }

        @Test
        @DisplayName("Reference instrument data is on the classpath")
        void instrumentRefOnClasspath() {
            try (InputStream is =
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream(
                                    "db/seed/reference/R__reference_instruments.sql")) {
                assertThat(is)
                        .as("R__reference_instruments.sql must be on the classpath")
                        .isNotNull();
            } catch (IOException e) {
                throw new AssertionError("Failed to read seed resource", e);
            }
        }

        @Test
        @DisplayName("Reference venue data is on the classpath")
        void venueRefOnClasspath() {
            try (InputStream is =
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream("db/seed/reference/R__reference_venues.sql")) {
                assertThat(is).as("R__reference_venues.sql must be on the classpath").isNotNull();
            } catch (IOException e) {
                throw new AssertionError("Failed to read seed resource", e);
            }
        }
    }

    // ── Helpers ──

    private String readClasspathResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(is).as("Resource '%s' must be on the classpath", path).isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
