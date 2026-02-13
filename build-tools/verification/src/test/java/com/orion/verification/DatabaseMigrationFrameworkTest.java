package com.orion.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Structural verification tests for the database migration framework (US-01-10).
 *
 * <p>
 * WHY: Ensures the Flyway migration framework is correctly set up with versioned SQL files,
 * multi-database support, seed data, and proper Spring Boot integration. These tests codify the
 * story's acceptance criteria so any structural regression is caught by CI.
 *
 * <p>
 * REINTERPRETATION: The original story specified node-pg-migrate (TypeScript). We use Flyway 10.x
 * with Spring Boot auto-configuration — the standard Java equivalent. SQL migration files are
 * identical; only the tooling changes.
 */
@DisplayName("US-01-10: Database Migration Framework")
class DatabaseMigrationFrameworkTest {

    private static Path projectRoot;
    private static Path databaseModule;

    @BeforeAll
    static void resolveProjectRoot() {
        projectRoot = Path.of(System.getProperty("user.dir")).getParent().getParent();
        databaseModule = projectRoot.resolve("libs/database");

        assertThat(projectRoot.resolve("pom.xml"))
                .as("Root pom.xml must exist at project root: %s", projectRoot).exists();
    }

    // ================================================================
    // AC1: Migration Tool Setup (Flyway installed and configured)
    // ================================================================
    @Nested
    @DisplayName("AC1: Migration Tool Setup")
    class MigrationToolSetup {

        @Test
        @DisplayName("libs/database module directory exists")
        void databaseModuleExists() {
            assertThat(databaseModule).exists().isDirectory();
        }

        @Test
        @DisplayName("libs/database/pom.xml exists with Flyway dependency")
        void databasePomExistsWithFlyway() throws IOException {
            Path pom = databaseModule.resolve("pom.xml");
            assertThat(pom).exists().isRegularFile();

            String content = Files.readString(pom);
            assertThat(content).as("POM must declare flyway-core dependency")
                    .contains("flyway-core");
            assertThat(content).as("POM must declare flyway-database-postgresql dependency")
                    .contains("flyway-database-postgresql");
        }

        @Test
        @DisplayName("Migration scripts base directory exists")
        void migrationScriptsDirectoryExists() {
            Path migrationDir = databaseModule.resolve("src/main/resources/db/migration");
            assertThat(migrationDir).exists().isDirectory();
        }

        @Test
        @DisplayName("Root POM includes libs/database module")
        void rootPomIncludesDatabaseModule() throws IOException {
            String rootPom = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(rootPom).as("Root POM must declare libs/database module")
                    .contains("<module>libs/database</module>");
        }

        @Test
        @DisplayName("Spring Boot Flyway auto-configuration properties exist")
        void flywayConfigurationInApplicationYml() throws IOException {
            Path appYml = databaseModule.resolve("src/main/resources/application-flyway.yml");
            assertThat(appYml).exists().isRegularFile();

            String content = Files.readString(appYml);
            assertThat(content).as("Must contain Flyway configuration section").contains("flyway");
        }
    }

    // ================================================================
    // AC2: Migration Commands (Flyway Maven plugin goals)
    // ================================================================
    @Nested
    @DisplayName("AC2: Migration Commands")
    class MigrationCommands {

        @Test
        @DisplayName("Database module POM contains Flyway Maven plugin")
        void flywayMavenPluginConfigured() throws IOException {
            String pom = Files.readString(databaseModule.resolve("pom.xml"));
            assertThat(pom).as("POM must declare flyway-maven-plugin")
                    .contains("flyway-maven-plugin");
        }

        @Test
        @DisplayName("README documents Maven migration commands")
        void readmeDocumentsMigrationCommands() throws IOException {
            Path readme = databaseModule.resolve("README.md");
            assertThat(readme).exists().isRegularFile();

            String content = Files.readString(readme);
            assertThat(content).contains("flyway:migrate");
            assertThat(content).contains("flyway:info");
            assertThat(content).contains("flyway:clean");
        }
    }

    // ================================================================
    // AC3: Seed Data
    // ================================================================
    @Nested
    @DisplayName("AC3: Seed Data")
    class SeedData {

        @Test
        @DisplayName("Development seed data directory exists")
        void developmentSeedDirectoryExists() {
            Path seedDir = databaseModule.resolve("src/main/resources/db/seed/development");
            assertThat(seedDir).exists().isDirectory();
        }

        @Test
        @DisplayName("Reference seed data directory exists")
        void referenceSeedDirectoryExists() {
            Path seedDir = databaseModule.resolve("src/main/resources/db/seed/reference");
            assertThat(seedDir).exists().isDirectory();
        }

        @Test
        @DisplayName("Tenant seed data exists")
        void tenantSeedDataExists() throws IOException {
            Path seedDir = databaseModule.resolve("src/main/resources/db/seed/development");
            try (Stream<Path> files = Files.list(seedDir)) {
                List<String> seedFiles = files.map(p -> p.getFileName().toString())
                        .filter(name -> name.contains("tenant")).toList();
                assertThat(seedFiles).as("Must have at least one tenant seed file").isNotEmpty();
            }
        }

        @Test
        @DisplayName("User seed data exists")
        void userSeedDataExists() throws IOException {
            Path seedDir = databaseModule.resolve("src/main/resources/db/seed/development");
            try (Stream<Path> files = Files.list(seedDir)) {
                List<String> seedFiles = files.map(p -> p.getFileName().toString())
                        .filter(name -> name.contains("user")).toList();
                assertThat(seedFiles).as("Must have at least one user seed file").isNotEmpty();
            }
        }

        @Test
        @DisplayName("Reference instrument data exists")
        void referenceInstrumentDataExists() throws IOException {
            Path seedDir = databaseModule.resolve("src/main/resources/db/seed/reference");
            try (Stream<Path> files = Files.list(seedDir)) {
                List<String> seedFiles = files.map(p -> p.getFileName().toString())
                        .filter(name -> name.contains("instrument")).toList();
                assertThat(seedFiles).as("Must have at least one instrument reference file")
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("Reference venue data exists")
        void referenceVenueDataExists() throws IOException {
            Path seedDir = databaseModule.resolve("src/main/resources/db/seed/reference");
            try (Stream<Path> files = Files.list(seedDir)) {
                List<String> seedFiles = files.map(p -> p.getFileName().toString())
                        .filter(name -> name.contains("venue")).toList();
                assertThat(seedFiles).as("Must have at least one venue reference file")
                        .isNotEmpty();
            }
        }
    }

    // ================================================================
    // AC4: CI Integration
    // ================================================================
    @Nested
    @DisplayName("AC4: CI Integration")
    class CiIntegration {

        @Test
        @DisplayName("Test profile configuration exists for Flyway")
        void testProfileConfigExists() throws IOException {
            // Flyway should be disabled or use embedded DB in test profile
            Path testYml = databaseModule.resolve("src/main/resources/application-test.yml");
            // Test config can also be in test resources
            Path testResourceYml =
                    databaseModule.resolve("src/test/resources/application-test.yml");

            assertThat(testYml.toFile().exists() || testResourceYml.toFile().exists())
                    .as("Test profile configuration must exist for Flyway").isTrue();
        }

        @Test
        @DisplayName("Flyway migration SQL files follow versioned naming convention")
        void migrationFilesFollowNamingConvention() throws IOException {
            Path orionMigrations = databaseModule.resolve("src/main/resources/db/migration/orion");

            // Flyway naming: V{version}__{description}.sql
            Pattern flywayPattern = Pattern.compile("V\\d+__[a-z_]+\\.sql");

            try (Stream<Path> files = Files.list(orionMigrations)) {
                List<String> sqlFiles = files.map(p -> p.getFileName().toString())
                        .filter(name -> name.endsWith(".sql")).toList();

                assertThat(sqlFiles).as("Must have at least one versioned migration file")
                        .isNotEmpty();

                for (String file : sqlFiles) {
                    assertThat(flywayPattern.matcher(file).matches())
                            .as("File '%s' must follow Flyway naming V{n}__{desc}.sql", file)
                            .isTrue();
                }
            }
        }
    }

    // ================================================================
    // AC5: Multi-Database Support
    // ================================================================
    @Nested
    @DisplayName("AC5: Multi-Database Support")
    class MultiDatabaseSupport {

        @Test
        @DisplayName("Orion (main) migration directory exists")
        void orionMigrationDirExists() {
            assertThat(databaseModule.resolve("src/main/resources/db/migration/orion")).exists()
                    .isDirectory();
        }

        @Test
        @DisplayName("RFQ migration directory exists (placeholder)")
        void rfqMigrationDirExists() {
            assertThat(databaseModule.resolve("src/main/resources/db/migration/rfq")).exists()
                    .isDirectory();
        }

        @Test
        @DisplayName("Market data migration directory exists (placeholder)")
        void marketdataMigrationDirExists() {
            assertThat(databaseModule.resolve("src/main/resources/db/migration/marketdata"))
                    .exists().isDirectory();
        }

        @Test
        @DisplayName("Multi-database Flyway configuration class exists")
        void multiDatabaseConfigClassExists() {
            Path configClass = databaseModule.resolve("src/main/java/com/orion/database/migration/"
                    + "FlywayMultiDatabaseConfig.java");
            assertThat(configClass).as("FlywayMultiDatabaseConfig.java must exist").exists()
                    .isRegularFile();
        }
    }

    // ================================================================
    // AC6: Initial Schemas
    // ================================================================
    @Nested
    @DisplayName("AC6: Initial Schemas")
    class InitialSchemas {

        @Test
        @DisplayName("Initial schema migration creates tenants table")
        void initialSchemaHasTenantsTable() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).as("Initial schema must create tenants table")
                    .containsIgnoringCase("CREATE TABLE tenants");
        }

        @Test
        @DisplayName("Initial schema migration creates users table")
        void initialSchemaHasUsersTable() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).as("Initial schema must create users table")
                    .containsIgnoringCase("CREATE TABLE users");
        }

        @Test
        @DisplayName("Initial schema migration creates user_roles table")
        void initialSchemaHasUserRolesTable() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).as("Initial schema must create user_roles table")
                    .containsIgnoringCase("CREATE TABLE user_roles");
        }

        @Test
        @DisplayName("Initial schema migration creates user_entitlements table")
        void initialSchemaHasUserEntitlementsTable() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).as("Initial schema must create user_entitlements table")
                    .containsIgnoringCase("CREATE TABLE user_entitlements");
        }

        @Test
        @DisplayName("Initial schema migration creates outbox_events table")
        void initialSchemaHasOutboxEventsTable() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).as("Initial schema must create outbox_events table")
                    .containsIgnoringCase("CREATE TABLE outbox_events");
        }

        @Test
        @DisplayName("Initial schema migration creates processed_events table")
        void initialSchemaHasProcessedEventsTable() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).as("Initial schema must create processed_events table")
                    .containsIgnoringCase("CREATE TABLE processed_events");
        }

        @Test
        @DisplayName("Initial schema migration creates audit_log table")
        void initialSchemaHasAuditLogTable() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).as("Initial schema must create audit_log table")
                    .containsIgnoringCase("CREATE TABLE audit_log");
        }

        @Test
        @DisplayName("Initial schema includes update_updated_at trigger function")
        void initialSchemaHasUpdateTriggerFunction() throws IOException {
            // Trigger function is in V2__initial_triggers.sql, so read all migrations
            String sql = readAllOrionMigrationsSql();
            assertThat(sql).as("Must define update_updated_at trigger function")
                    .containsIgnoringCase("update_updated_at");
        }

        @Test
        @DisplayName("Initial schema includes indexes for performance")
        void initialSchemaHasIndexes() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).as("Must create indexes").containsIgnoringCase("CREATE INDEX");
        }

        @Test
        @DisplayName("Outbox events table supports reliable event publishing pattern")
        void outboxTableHasRequiredColumns() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).contains("event_type");
            assertThat(sql).contains("event_id");
            assertThat(sql).contains("payload");
            assertThat(sql).contains("published_at");
            assertThat(sql).contains("retry_count");
        }

        @Test
        @DisplayName("Users table enforces tenant-scoped uniqueness")
        void usersTableHasTenantScopedUniqueness() throws IOException {
            String sql = readInitialSchemaSql();
            assertThat(sql).as("Users table must enforce unique (tenant_id, email)")
                    .containsIgnoringCase("UNIQUE(tenant_id, email)");
            assertThat(sql).as("Users table must enforce unique (tenant_id, username)")
                    .containsIgnoringCase("UNIQUE(tenant_id, username)");
        }

        // ── Helpers ──

        private String readInitialSchemaSql() throws IOException {
            Path orionDir = databaseModule.resolve("src/main/resources/db/migration/orion");
            // Find the V1__initial_schema.sql file
            try (Stream<Path> files = Files.list(orionDir)) {
                Path initialSchema = files
                        .filter(p -> p.getFileName().toString().contains("initial_schema"))
                        .findFirst().orElseThrow(
                                () -> new AssertionError("V1__initial_schema.sql not" + " found"));
                return Files.readString(initialSchema);
            }
        }

        /** Reads ALL .sql files in the orion migration directory and concatenates them. */
        private String readAllOrionMigrationsSql() throws IOException {
            Path orionDir = databaseModule.resolve("src/main/resources/db/migration/orion");
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> files = Files.list(orionDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted()
                        .forEach(p -> {
                            try {
                                sb.append(Files.readString(p)).append("\n");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
            return sb.toString();
        }
    }
}
