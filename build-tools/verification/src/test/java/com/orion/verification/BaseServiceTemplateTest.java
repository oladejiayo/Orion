package com.orion.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Structural verification tests for the base service template (US-01-09).
 *
 * <p>WHY: The original story specifies Express, KafkaJS, Zod, and main.ts for TypeScript/Node.js.
 * We reinterpret for Java 21 + Spring Boot 3.x: - Express HTTP server → Spring Boot Web (embedded
 * Tomcat) - gRPC server init → gRPC interceptors (standalone, starter added per service) - KafkaJS
 * → Spring Kafka (config template, wired per service) - Zod config validation
 * → @ConfigurationProperties + Bean Validation - main.ts entry point → @SpringBootApplication main
 * class - Health endpoints → Spring Boot Actuator (/actuator/health) - Metrics endpoint → Actuator
 * + Micrometer Prometheus registry - Correlation middleware → OncePerRequestFilter + gRPC
 * ServerInterceptor - Error middleware → @RestControllerAdvice with RFC 7807 ProblemDetail -
 * Graceful shutdown → server.shutdown=graceful (built-in)
 *
 * <p>These tests verify the template structure stays intact. Any accidental deletion or
 * misconfiguration is caught at build time.
 */
@DisplayName("US-01-09: Base Service Template")
class BaseServiceTemplateTest {

    private static Path projectRoot;
    private static Path serviceTemplate;
    private static String templatePom;

    @BeforeAll
    static void resolveProjectRoot() throws IOException {
        projectRoot = Path.of(System.getProperty("user.dir")).getParent().getParent();
        serviceTemplate = projectRoot.resolve("services/service-template");
        assertThat(projectRoot.resolve("pom.xml"))
                .as("Root pom.xml must exist at project root: %s", projectRoot)
                .exists();
        templatePom = Files.readString(serviceTemplate.resolve("pom.xml"));
    }

    // ================================================================
    // AC1: Service Structure
    // ================================================================
    @Nested
    @DisplayName("AC1: Service Structure")
    class ServiceStructure {

        @Test
        @DisplayName("Service template directory exists")
        void serviceTemplateDirectoryExists() {
            assertThat(serviceTemplate)
                    .as("services/service-template/ must exist")
                    .exists()
                    .isDirectory();
        }

        @Test
        @DisplayName("POM file exists")
        void pomXmlExists() {
            assertThat(serviceTemplate.resolve("pom.xml"))
                    .as("Service template must have a pom.xml")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Main application class exists")
        void mainApplicationClassExists() {
            assertThat(
                            serviceTemplate.resolve(
                                    "src/main/java/com/orion/servicetemplate/ServiceTemplateApplication.java"))
                    .as("Spring Boot main class must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("README documentation exists")
        void readmeExists() {
            assertThat(serviceTemplate.resolve("README.md"))
                    .as("Service template must have a README")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Default application.yml exists")
        void applicationYmlExists() {
            assertThat(serviceTemplate.resolve("src/main/resources/application.yml"))
                    .as("Default Spring Boot configuration must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Local development profile exists")
        void applicationLocalYmlExists() {
            assertThat(serviceTemplate.resolve("src/main/resources/application-local.yml"))
                    .as("Local dev profile must exist for docker-compose connectivity")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Docker profile exists")
        void applicationDockerYmlExists() {
            assertThat(serviceTemplate.resolve("src/main/resources/application-docker.yml"))
                    .as("Docker profile must exist for container networking")
                    .exists()
                    .isRegularFile();
        }
    }

    // ================================================================
    // AC2: HTTP Server Infrastructure
    // ================================================================
    @Nested
    @DisplayName("AC2: HTTP Infrastructure")
    class HttpInfrastructure {

        @Test
        @DisplayName("Correlation ID filter exists")
        void correlationIdFilterExists() {
            assertThat(
                            serviceTemplate.resolve(
                                    "src/main/java/com/orion/servicetemplate/infrastructure/web/CorrelationIdFilter.java"))
                    .as("Correlation ID servlet filter must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Global exception handler exists")
        void globalExceptionHandlerExists() {
            assertThat(
                            serviceTemplate.resolve(
                                    "src/main/java/com/orion/servicetemplate/infrastructure/web/GlobalExceptionHandler.java"))
                    .as("@RestControllerAdvice error handler must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Web configuration exists")
        void webConfigExists() {
            assertThat(
                            serviceTemplate.resolve(
                                    "src/main/java/com/orion/servicetemplate/config/WebConfig.java"))
                    .as("CORS / Web MVC configuration must exist")
                    .exists()
                    .isRegularFile();
        }
    }

    // ================================================================
    // AC3: gRPC Server Infrastructure
    // ================================================================
    @Nested
    @DisplayName("AC3: gRPC Infrastructure")
    class GrpcInfrastructure {

        @Test
        @DisplayName("gRPC correlation interceptor exists")
        void grpcCorrelationInterceptorExists() {
            assertThat(
                            serviceTemplate.resolve(
                                    "src/main/java/com/orion/servicetemplate/infrastructure/grpc/GrpcCorrelationInterceptor.java"))
                    .as("gRPC correlation propagation interceptor must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("gRPC exception interceptor exists")
        void grpcExceptionInterceptorExists() {
            assertThat(
                            serviceTemplate.resolve(
                                    "src/main/java/com/orion/servicetemplate/infrastructure/grpc/GrpcExceptionInterceptor.java"))
                    .as("gRPC exception mapping interceptor must exist")
                    .exists()
                    .isRegularFile();
        }
    }

    // ================================================================
    // AC7: Configuration
    // ================================================================
    @Nested
    @DisplayName("AC7: Configuration")
    class ConfigurationManagement {

        @Test
        @DisplayName("Configuration properties class exists")
        void propertiesClassExists() {
            assertThat(
                            serviceTemplate.resolve(
                                    "src/main/java/com/orion/servicetemplate/config/ServiceTemplateProperties.java"))
                    .as("@ConfigurationProperties class must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("POM includes spring-boot-starter-web")
        void pomIncludesSpringBootWeb() {
            assertThat(templatePom)
                    .as("Service must depend on Spring Boot Web")
                    .contains("spring-boot-starter-web");
        }

        @Test
        @DisplayName("POM includes spring-boot-starter-actuator")
        void pomIncludesActuator() {
            assertThat(templatePom)
                    .as("Service must depend on Actuator for health/metrics")
                    .contains("spring-boot-starter-actuator");
        }

        @Test
        @DisplayName("POM references Orion shared libraries")
        void pomReferencesOrionLibs() {
            assertThat(templatePom)
                    .as("Service must depend on orion-event-model")
                    .contains("orion-event-model");
            assertThat(templatePom)
                    .as("Service must depend on orion-security")
                    .contains("orion-security");
            assertThat(templatePom)
                    .as("Service must depend on orion-observability")
                    .contains("orion-observability");
        }
    }

    // ================================================================
    // AC8: Clean Architecture Package Layout
    // ================================================================
    @Nested
    @DisplayName("AC8: Clean Architecture Packages")
    class CleanArchitecturePackages {

        private final Path javaRoot =
                serviceTemplate.resolve("src/main/java/com/orion/servicetemplate");

        @Test
        @DisplayName("api package exists")
        void apiPackageExists() {
            assertThat(javaRoot.resolve("api"))
                    .as("API layer package must exist (REST controllers)")
                    .exists()
                    .isDirectory();
        }

        @Test
        @DisplayName("domain package exists")
        void domainPackageExists() {
            assertThat(javaRoot.resolve("domain"))
                    .as("Domain layer package must exist (business logic)")
                    .exists()
                    .isDirectory();
        }

        @Test
        @DisplayName("infrastructure package exists")
        void infrastructurePackageExists() {
            assertThat(javaRoot.resolve("infrastructure"))
                    .as("Infrastructure layer package must exist (web, gRPC, DB)")
                    .exists()
                    .isDirectory();
        }

        @Test
        @DisplayName("config package exists")
        void configPackageExists() {
            assertThat(javaRoot.resolve("config"))
                    .as("Configuration package must exist")
                    .exists()
                    .isDirectory();
        }
    }
}
