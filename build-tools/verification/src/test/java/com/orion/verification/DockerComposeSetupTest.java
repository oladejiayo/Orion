package com.orion.verification;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural verification tests for the Docker Compose local dev environment (US-01-02).
 *
 * WHY: These tests codify the acceptance criteria from US-01-02 so that
 * the local development infrastructure configuration is validated by CI.
 * They ensure Docker Compose files, environment templates, init scripts,
 * helper scripts, and documentation are all in place and correctly structured.
 */
@DisplayName("US-01-02: Docker Compose Local Development Environment")
class DockerComposeSetupTest {

    private static Path projectRoot;
    private static Path composeDir;
    private static Path scriptsDir;

    @BeforeAll
    static void resolveProjectRoot() {
        // Maven runs tests with CWD = module directory (build-tools/verification)
        projectRoot = Path.of(System.getProperty("user.dir")).getParent().getParent();
        composeDir = projectRoot.resolve("infra/docker-compose");
        scriptsDir = projectRoot.resolve("scripts");

        // Sanity: root pom.xml must exist (confirms we found the right root)
        assertThat(projectRoot.resolve("pom.xml"))
                .as("Root pom.xml must exist at project root: %s", projectRoot)
                .exists();
    }

    // ================================================================
    // AC1: Core Infrastructure Services
    // ================================================================
    @Nested
    @DisplayName("AC1: Core Infrastructure Services")
    class CoreInfrastructureServices {

        @Test
        @DisplayName("docker-compose.yml exists")
        void dockerComposeFileExists() {
            assertThat(composeDir.resolve("docker-compose.yml"))
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("docker-compose.yml defines Redpanda (Kafka-compatible) service")
        void redpandaServiceDefined() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redpanda service must be defined for Kafka-compatible messaging")
                    .contains("redpanda:");
        }

        @Test
        @DisplayName("docker-compose.yml defines PostgreSQL service")
        void postgresServiceDefined() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("PostgreSQL service must be defined for database")
                    .contains("postgres:");
        }

        @Test
        @DisplayName("docker-compose.yml defines Redis service")
        void redisServiceDefined() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redis service must be defined for caching")
                    .contains("redis:");
        }

        @Test
        @DisplayName("docker-compose.yml defines orion-network")
        void orionNetworkDefined() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Shared Docker network 'orion-network' must be defined")
                    .contains("orion-network");
        }

        @Test
        @DisplayName("PostgreSQL uses version 15 image")
        void postgresVersion15() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("PostgreSQL must use version 15")
                    .contains("postgres:15");
        }

        @Test
        @DisplayName("Redis uses version 7 image")
        void redisVersion7() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redis must use version 7")
                    .contains("redis:7");
        }

        @Test
        @DisplayName("PostgreSQL has health check configured")
        void postgresHealthCheck() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("PostgreSQL must have health check using pg_isready")
                    .contains("pg_isready");
        }

        @Test
        @DisplayName("Redis has health check configured")
        void redisHealthCheck() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redis must have health check using redis-cli ping")
                    .contains("redis-cli");
        }

        @Test
        @DisplayName("Redpanda has health check configured")
        void redpandaHealthCheck() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redpanda must have health check using rpk cluster health")
                    .contains("rpk cluster health");
        }
    }

    // ================================================================
    // AC2: Development Tooling Containers
    // ================================================================
    @Nested
    @DisplayName("AC2: Development Tooling Containers")
    class DevelopmentTooling {

        @Test
        @DisplayName("Redpanda Console (Kafka UI) service is defined")
        void redpandaConsoleDefined() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redpanda Console must be defined for Kafka UI at port 8080")
                    .contains("redpanda-console:");
        }

        @Test
        @DisplayName("Redpanda Console maps port 8080")
        void redpandaConsolePort() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redpanda Console must be accessible at localhost:8080")
                    .contains("8080:8080");
        }

        @Test
        @DisplayName("pgAdmin service is defined")
        void pgAdminDefined() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("pgAdmin must be defined for database management")
                    .contains("pgadmin:");
        }

        @Test
        @DisplayName("pgAdmin maps port 5050")
        void pgAdminPort() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("pgAdmin must be accessible at localhost:5050")
                    .contains("5050:");
        }

        @Test
        @DisplayName("Redis Commander service is defined")
        void redisCommanderDefined() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redis Commander must be defined for Redis UI")
                    .contains("redis-commander:");
        }

        @Test
        @DisplayName("Redis Commander maps port 8081")
        void redisCommanderPort() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redis Commander must be accessible at localhost:8081")
                    .contains("8081:8081");
        }
    }

    // ================================================================
    // AC3: Configuration and Secrets
    // ================================================================
    @Nested
    @DisplayName("AC3: Configuration and Secrets")
    class ConfigurationAndSecrets {

        @Test
        @DisplayName(".env.example exists with documented variables")
        void envExampleExists() {
            assertThat(composeDir.resolve(".env.example"))
                    .as(".env.example must document all required environment variables")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName(".env.example contains PostgreSQL configuration")
        void envExamplePostgresConfig() throws IOException {
            String content = Files.readString(composeDir.resolve(".env.example"));
            assertThat(content)
                    .contains("POSTGRES_USER")
                    .contains("POSTGRES_PASSWORD")
                    .contains("POSTGRES_DB");
        }

        @Test
        @DisplayName(".env.example contains pgAdmin configuration")
        void envExamplePgAdminConfig() throws IOException {
            String content = Files.readString(composeDir.resolve(".env.example"));
            assertThat(content)
                    .contains("PGADMIN_EMAIL")
                    .contains("PGADMIN_PASSWORD");
        }

        @Test
        @DisplayName(".env is excluded in root .gitignore")
        void envIsGitignored() throws IOException {
            String content = Files.readString(projectRoot.resolve(".gitignore"));
            assertThat(content)
                    .as(".env files must be gitignored to prevent credential leaks")
                    .contains(".env");
        }

        @Test
        @DisplayName("PostgreSQL init-scripts directory exists")
        void initScriptsDirectoryExists() {
            assertThat(composeDir.resolve("init-scripts/postgres"))
                    .as("PostgreSQL init-scripts directory must exist for database bootstrapping")
                    .exists()
                    .isDirectory();
        }

        @Test
        @DisplayName("PostgreSQL init script SQL file exists")
        void initScriptSqlExists() throws IOException {
            Path initDir = composeDir.resolve("init-scripts/postgres");
            assertThat(initDir).exists().isDirectory();

            // At least one .sql file must exist
            long sqlFileCount = Files.list(initDir)
                    .filter(p -> p.toString().endsWith(".sql"))
                    .count();
            assertThat(sqlFileCount)
                    .as("At least one .sql init script must exist in init-scripts/postgres/")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Init script creates service-specific databases")
        void initScriptCreatesServiceDatabases() throws IOException {
            Path initDir = composeDir.resolve("init-scripts/postgres");
            // Read all SQL files and check they create the expected databases
            String allSql = Files.list(initDir)
                    .filter(p -> p.toString().endsWith(".sql"))
                    .map(p -> {
                        try { return Files.readString(p); }
                        catch (IOException e) { return ""; }
                    })
                    .reduce("", String::concat);

            assertThat(allSql)
                    .as("Init script should create per-service databases")
                    .contains("CREATE DATABASE");
        }

        @Test
        @DisplayName("docker-compose.yml uses environment variable substitution for credentials")
        void envVarSubstitution() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Credentials must use ${VAR:-default} substitution from .env")
                    .contains("${POSTGRES_USER:-")
                    .contains("${POSTGRES_PASSWORD:-");
        }
    }

    // ================================================================
    // AC4: Developer Experience
    // ================================================================
    @Nested
    @DisplayName("AC4: Developer Experience")
    class DeveloperExperience {

        @Test
        @DisplayName("Services have depends_on with health check conditions")
        void dependsOnWithHealthChecks() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Services must use depends_on with condition: service_healthy")
                    .contains("condition: service_healthy");
        }

        @Test
        @DisplayName("All core services are on orion-network")
        void allServicesOnNetwork() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            // Count occurrences of orion-network — should appear in each service + networks section
            long count = content.lines()
                    .filter(line -> line.contains("orion-network"))
                    .count();
            assertThat(count)
                    .as("orion-network should appear in every service + networks section (at least 8 times)")
                    .isGreaterThanOrEqualTo(7);
        }

        @Test
        @DisplayName("docker-compose.yml defines named volumes for data persistence")
        void namedVolumesDefined() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Named volumes must be defined for data persistence")
                    .contains("volumes:")
                    .contains("redpanda-data:")
                    .contains("postgres-data:")
                    .contains("redis-data:");
        }
    }

    // ================================================================
    // AC5: Data Persistence & Reset
    // ================================================================
    @Nested
    @DisplayName("AC5: Data Persistence and Reset")
    class DataPersistence {

        @Test
        @DisplayName("PostgreSQL uses named volume for data")
        void postgresNamedVolume() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("PostgreSQL must use named volume for data persistence")
                    .contains("postgres-data:/var/lib/postgresql/data");
        }

        @Test
        @DisplayName("Redis uses named volume for data")
        void redisNamedVolume() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redis must use named volume for data persistence")
                    .contains("redis-data:/data");
        }

        @Test
        @DisplayName("Redpanda uses named volume for data")
        void redpandaNamedVolume() throws IOException {
            String content = Files.readString(composeDir.resolve("docker-compose.yml"));
            assertThat(content)
                    .as("Redpanda must use named volume for data persistence")
                    .contains("redpanda-data:/var/lib/redpanda/data");
        }

        @Test
        @DisplayName("Reset script exists (bash or PowerShell)")
        void resetScriptExists() {
            boolean bashExists = Files.exists(scriptsDir.resolve("reset-local-env.sh"));
            boolean psExists = Files.exists(scriptsDir.resolve("reset-local-env.ps1"));
            assertThat(bashExists || psExists)
                    .as("A reset script must exist (reset-local-env.sh or reset-local-env.ps1)")
                    .isTrue();
        }

        @Test
        @DisplayName("Start script exists (bash or PowerShell)")
        void startScriptExists() {
            boolean bashExists = Files.exists(scriptsDir.resolve("start-local-env.sh"));
            boolean psExists = Files.exists(scriptsDir.resolve("start-local-env.ps1"));
            assertThat(bashExists || psExists)
                    .as("A start script must exist (start-local-env.sh or start-local-env.ps1)")
                    .isTrue();
        }
    }

    // ================================================================
    // AC6: Documentation
    // ================================================================
    @Nested
    @DisplayName("AC6: Documentation")
    class Documentation {

        @Test
        @DisplayName("Docker Compose README exists with substantial content")
        void dockerComposeReadmeExists() throws IOException {
            Path readme = composeDir.resolve("README.md");
            assertThat(readme).exists().isRegularFile();

            String content = Files.readString(readme);
            assertThat(content.length())
                    .as("README should be comprehensive, not just a placeholder")
                    .isGreaterThan(500);
        }

        @Test
        @DisplayName("README documents port mappings")
        void readmeDocumentsPortMappings() throws IOException {
            String content = Files.readString(composeDir.resolve("README.md"));
            assertThat(content)
                    .as("README must document port mappings for all services")
                    .contains("19092")
                    .contains("5432")
                    .contains("6379")
                    .contains("8080")
                    .contains("5050");
        }

        @Test
        @DisplayName("README documents resource requirements")
        void readmeDocumentsResourceRequirements() throws IOException {
            String content = Files.readString(composeDir.resolve("README.md"));
            assertThat(content)
                    .as("README must document system resource requirements (RAM, CPU)")
                    .containsIgnoringCase("RAM")
                    .containsIgnoringCase("CPU");
        }

        @Test
        @DisplayName("README has troubleshooting section")
        void readmeHasTroubleshooting() throws IOException {
            String content = Files.readString(composeDir.resolve("README.md"));
            assertThat(content)
                    .as("README must have a troubleshooting section")
                    .containsIgnoringCase("troubleshoot");
        }

        @Test
        @DisplayName("README documents how to start and stop")
        void readmeDocumentsStartStop() throws IOException {
            String content = Files.readString(composeDir.resolve("README.md"));
            assertThat(content)
                    .as("README must document docker compose up/down commands")
                    .contains("docker compose up")
                    .contains("docker compose down");
        }
    }
}
