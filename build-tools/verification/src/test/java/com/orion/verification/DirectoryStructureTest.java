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
 * Structural verification tests for the Orion monorepo (US-01-01).
 *
 * WHY: These tests codify the PRD's directory structure requirements
 * so that any accidental deletion or misconfiguration is caught by CI.
 * They run as part of the normal Maven build lifecycle.
 */
@DisplayName("US-01-01: Monorepo Directory Structure")
class DirectoryStructureTest {

    /**
     * The project root — two levels up from build-tools/verification/.
     * We resolve this from the current working directory which Maven sets
     * to the module directory during test execution.
     */
    private static Path projectRoot;

    @BeforeAll
    static void resolveProjectRoot() {
        // Maven runs tests with CWD = module directory (build-tools/verification)
        // We need to go up 2 levels to reach the monorepo root.
        projectRoot = Path.of(System.getProperty("user.dir")).getParent().getParent();

        // Sanity check: the root pom.xml must exist
        assertThat(projectRoot.resolve("pom.xml"))
                .as("Root pom.xml must exist at project root: %s", projectRoot)
                .exists();
    }

    // ================================================================
    // AC1: Repository Root Structure
    // ================================================================
    @Nested
    @DisplayName("AC1: Repository Root Structure")
    class RootStructure {

        @Test
        @DisplayName("Root pom.xml exists")
        void rootPomExists() {
            assertThat(projectRoot.resolve("pom.xml")).exists().isRegularFile();
        }

        @Test
        @DisplayName("Maven wrapper script (mvnw) exists")
        void mavenWrapperExists() {
            assertThat(projectRoot.resolve("mvnw")).exists().isRegularFile();
        }

        @Test
        @DisplayName("Maven wrapper CMD script (mvnw.cmd) exists")
        void mavenWrapperCmdExists() {
            assertThat(projectRoot.resolve("mvnw.cmd")).exists().isRegularFile();
        }

        @Test
        @DisplayName(".mvn/wrapper directory exists")
        void mavenWrapperDirectoryExists() {
            assertThat(projectRoot.resolve(".mvn/wrapper")).exists().isDirectory();
        }

        @Test
        @DisplayName(".gitignore exists")
        void gitignoreExists() {
            assertThat(projectRoot.resolve(".gitignore")).exists().isRegularFile();
        }

        @Test
        @DisplayName(".editorconfig exists")
        void editorconfigExists() {
            assertThat(projectRoot.resolve(".editorconfig")).exists().isRegularFile();
        }

        @Test
        @DisplayName("README.md exists at root")
        void readmeExists() {
            assertThat(projectRoot.resolve("README.md")).exists().isRegularFile();
        }
    }

    // ================================================================
    // AC2: Workspace Configuration (Maven multi-module)
    // ================================================================
    @Nested
    @DisplayName("AC2: Maven Multi-Module Configuration")
    class MavenMultiModule {

        @Test
        @DisplayName("Root POM has groupId com.orion")
        void rootPomGroupId() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("<groupId>com.orion</groupId>");
        }

        @Test
        @DisplayName("Root POM has artifactId orion-platform")
        void rootPomArtifactId() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("<artifactId>orion-platform</artifactId>");
        }

        @Test
        @DisplayName("Root POM packaging is 'pom' (multi-module)")
        void rootPomPackaging() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("<packaging>pom</packaging>");
        }

        @Test
        @DisplayName("Root POM inherits Spring Boot starter parent")
        void springBootParent() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("spring-boot-starter-parent");
        }

        @Test
        @DisplayName("Root POM specifies Java 21")
        void java21Configured() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("<java.version>21</java.version>");
        }

        @Test
        @DisplayName("Root POM has build-tools/verification module")
        void verificationModuleDeclared() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("<module>build-tools/verification</module>");
        }
    }

    // ================================================================
    // AC3: Directory Structure (PRD Section 18)
    // ================================================================
    @Nested
    @DisplayName("AC3: Directory Structure matches PRD")
    class DirectoryLayout {

        @Test
        @DisplayName("docs/ directory exists")
        void docsDirectoryExists() {
            assertThat(projectRoot.resolve("docs")).exists().isDirectory();
        }

        @Test
        @DisplayName("docs/prd/ directory exists")
        void docsPrdExists() {
            assertThat(projectRoot.resolve("docs/prd")).exists().isDirectory();
        }

        @Test
        @DisplayName("docs/architecture/ directory exists")
        void docsArchitectureExists() {
            assertThat(projectRoot.resolve("docs/architecture")).exists().isDirectory();
        }

        @Test
        @DisplayName("docs/runbooks/ directory exists")
        void docsRunbooksExists() {
            assertThat(projectRoot.resolve("docs/runbooks")).exists().isDirectory();
        }

        @Test
        @DisplayName("docs/adr/ directory exists")
        void docsAdrExists() {
            assertThat(projectRoot.resolve("docs/adr")).exists().isDirectory();
        }

        @Test
        @DisplayName("docs/api/ directory exists")
        void docsApiExists() {
            assertThat(projectRoot.resolve("docs/api")).exists().isDirectory();
        }

        @Test
        @DisplayName("services/ directory exists")
        void servicesDirectoryExists() {
            assertThat(projectRoot.resolve("services")).exists().isDirectory();
        }

        @Test
        @DisplayName("libs/ directory exists")
        void libsDirectoryExists() {
            assertThat(projectRoot.resolve("libs")).exists().isDirectory();
        }

        @Test
        @DisplayName("proto/v1/ directory exists")
        void protoDirectoryExists() {
            assertThat(projectRoot.resolve("proto/v1")).exists().isDirectory();
        }

        @Test
        @DisplayName("schemas/v1/ directory exists")
        void schemasDirectoryExists() {
            assertThat(projectRoot.resolve("schemas/v1")).exists().isDirectory();
        }

        @Test
        @DisplayName("infra/terraform/ directory exists")
        void infraTerraformExists() {
            assertThat(projectRoot.resolve("infra/terraform")).exists().isDirectory();
        }

        @Test
        @DisplayName("infra/docker-compose/ directory exists")
        void infraDockerComposeExists() {
            assertThat(projectRoot.resolve("infra/docker-compose")).exists().isDirectory();
        }

        @Test
        @DisplayName("benchmarks/ directory exists")
        void benchmarksDirectoryExists() {
            assertThat(projectRoot.resolve("benchmarks")).exists().isDirectory();
        }

        @Test
        @DisplayName("scripts/ directory exists")
        void scriptsDirectoryExists() {
            assertThat(projectRoot.resolve("scripts")).exists().isDirectory();
        }

        @Test
        @DisplayName("web/ directory exists")
        void webDirectoryExists() {
            assertThat(projectRoot.resolve("web")).exists().isDirectory();
        }

        @Test
        @DisplayName("build-tools/ directory exists")
        void buildToolsDirectoryExists() {
            assertThat(projectRoot.resolve("build-tools")).exists().isDirectory();
        }
    }

    // ================================================================
    // AC3 continued: README placeholders in each directory
    // ================================================================
    @Nested
    @DisplayName("AC3: README placeholders in each directory")
    class ReadmePlaceholders {

        @Test
        @DisplayName("docs/ has README.md")
        void docsReadme() {
            assertThat(projectRoot.resolve("docs/README.md")).exists().isRegularFile();
        }

        @Test
        @DisplayName("services/ has README.md")
        void servicesReadme() {
            assertThat(projectRoot.resolve("services/README.md")).exists().isRegularFile();
        }

        @Test
        @DisplayName("libs/ has README.md")
        void libsReadme() {
            assertThat(projectRoot.resolve("libs/README.md")).exists().isRegularFile();
        }

        @Test
        @DisplayName("proto/ has README.md")
        void protoReadme() {
            assertThat(projectRoot.resolve("proto/README.md")).exists().isRegularFile();
        }

        @Test
        @DisplayName("schemas/ has README.md")
        void schemasReadme() {
            assertThat(projectRoot.resolve("schemas/README.md")).exists().isRegularFile();
        }

        @Test
        @DisplayName("infra/ has README.md")
        void infraReadme() {
            assertThat(projectRoot.resolve("infra/README.md")).exists().isRegularFile();
        }

        @Test
        @DisplayName("benchmarks/ has README.md")
        void benchmarksReadme() {
            assertThat(projectRoot.resolve("benchmarks/README.md")).exists().isRegularFile();
        }

        @Test
        @DisplayName("scripts/ has README.md")
        void scriptsReadme() {
            assertThat(projectRoot.resolve("scripts/README.md")).exists().isRegularFile();
        }

        @Test
        @DisplayName("web/ has README.md")
        void webReadme() {
            assertThat(projectRoot.resolve("web/README.md")).exists().isRegularFile();
        }
    }

    // ================================================================
    // AC4: Build Commands (Maven wrapper configured correctly)
    // ================================================================
    @Nested
    @DisplayName("AC4: Build Commands Configuration")
    class BuildCommands {

        @Test
        @DisplayName("Maven wrapper properties file exists")
        void mavenWrapperPropertiesExist() {
            assertThat(projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties"))
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Maven wrapper properties reference Maven 3.9.x")
        void mavenWrapperVersion() throws IOException {
            String content = Files.readString(
                    projectRoot.resolve(".mvn/wrapper/maven-wrapper.properties"));
            assertThat(content).contains("maven-3.9");
        }
    }

    // ================================================================
    // AC5: Java / Spring Boot Configuration
    // ================================================================
    @Nested
    @DisplayName("AC5: Java & Spring Boot Configuration")
    class JavaConfiguration {

        @Test
        @DisplayName("Java version is 21")
        void javaVersionIs21() {
            // Runtime check: the JVM running these tests should be 21+
            int version = Runtime.version().feature();
            assertThat(version)
                    .as("Tests must run on Java 21+")
                    .isGreaterThanOrEqualTo(21);
        }

        @Test
        @DisplayName("Root POM has Testcontainers BOM in dependency management")
        void testcontainersBom() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("testcontainers-bom");
        }

        @Test
        @DisplayName("Root POM has gRPC BOM in dependency management")
        void grpcBom() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("grpc-bom");
        }

        @Test
        @DisplayName("Root POM configures maven-compiler-plugin with parameters flag")
        void compilerPluginParameters() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("<parameters>true</parameters>");
        }

        @Test
        @DisplayName("Root POM configures Surefire plugin for unit tests")
        void surefireConfigured() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("maven-surefire-plugin");
        }

        @Test
        @DisplayName("Root POM configures Failsafe plugin for integration tests")
        void failsafeConfigured() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("maven-failsafe-plugin");
        }

        @Test
        @DisplayName("Root POM configures JaCoCo for code coverage")
        void jacocoConfigured() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("jacoco-maven-plugin");
        }

        @Test
        @DisplayName("Root POM has UTF-8 encoding configured")
        void utf8Encoding() throws IOException {
            String pomContent = Files.readString(projectRoot.resolve("pom.xml"));
            assertThat(pomContent).contains("<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>");
        }
    }
}
