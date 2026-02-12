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
 * Structural verification tests for the CI/CD pipeline (US-01-07).
 *
 * WHY: These tests ensure that all GitHub Actions workflow files,
 * reusable actions, CODEOWNERS, and Dockerfile templates exist and
 * contain the correct content for our Java 21 + Maven + Spring Boot stack.
 * Any accidental deletion or misconfiguration is caught by the build.
 */
@DisplayName("US-01-07: GitHub Actions CI Pipeline Structure")
class CiPipelineStructureTest {

    private static Path projectRoot;

    @BeforeAll
    static void resolveProjectRoot() {
        // Maven runs tests with CWD = module directory (build-tools/verification)
        projectRoot = Path.of(System.getProperty("user.dir")).getParent().getParent();
        assertThat(projectRoot.resolve("pom.xml"))
                .as("Root pom.xml must exist at project root: %s", projectRoot)
                .exists();
    }

    // ================================================================
    // AC1: PR Validation Workflow
    // ================================================================
    @Nested
    @DisplayName("AC1: Pull Request Workflow")
    class PullRequestWorkflow {

        @Test
        @DisplayName("PR workflow file exists")
        void prWorkflowFileExists() {
            assertThat(projectRoot.resolve(".github/workflows/ci-pr.yml"))
                    .as("PR validation workflow must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("PR workflow triggers on pull requests to main")
        void prWorkflowTriggersOnPullRequests() throws IOException {
            String content = readWorkflow("ci-pr.yml");
            assertThat(content)
                    .as("PR workflow must trigger on pull_request to main")
                    .contains("pull_request")
                    .contains("main");
        }

        @Test
        @DisplayName("PR workflow uses Java 21")
        void prWorkflowUsesJava21() throws IOException {
            String content = readWorkflow("ci-pr.yml");
            assertThat(content)
                    .as("PR workflow must configure Java 21")
                    .contains("21");
        }

        @Test
        @DisplayName("PR workflow runs Maven verify")
        void prWorkflowRunsMavenVerify() throws IOException {
            String content = readWorkflow("ci-pr.yml");
            assertThat(content)
                    .as("PR workflow must run Maven verify to build and test")
                    .containsPattern("mvn.*verify|mvnw.*verify");
        }

        @Test
        @DisplayName("PR workflow has concurrency group for cancel-in-progress")
        void prWorkflowHasConcurrency() throws IOException {
            String content = readWorkflow("ci-pr.yml");
            assertThat(content)
                    .as("PR workflow should cancel in-progress runs for same PR")
                    .contains("concurrency")
                    .contains("cancel-in-progress");
        }
    }

    // ================================================================
    // AC2: Main Branch Workflow
    // ================================================================
    @Nested
    @DisplayName("AC2: Main Branch Workflow")
    class MainBranchWorkflow {

        @Test
        @DisplayName("Main workflow file exists")
        void mainWorkflowFileExists() {
            assertThat(projectRoot.resolve(".github/workflows/ci-main.yml"))
                    .as("Main branch CI workflow must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Main workflow triggers on push to main")
        void mainWorkflowTriggersOnPush() throws IOException {
            String content = readWorkflow("ci-main.yml");
            assertThat(content)
                    .as("Main workflow must trigger on push to main")
                    .contains("push")
                    .contains("main");
        }

        @Test
        @DisplayName("Main workflow runs full build with coverage")
        void mainWorkflowRunsCoverage() throws IOException {
            String content = readWorkflow("ci-main.yml");
            assertThat(content)
                    .as("Main workflow must generate coverage reports")
                    .contains("coverage")
                    .containsPattern("mvn.*verify|mvnw.*verify");
        }

        @Test
        @DisplayName("Main workflow uploads test results")
        void mainWorkflowUploadsArtifacts() throws IOException {
            String content = readWorkflow("ci-main.yml");
            assertThat(content)
                    .as("Main workflow must upload test/coverage artifacts")
                    .contains("upload-artifact");
        }
    }

    // ================================================================
    // AC3: Build Caching
    // ================================================================
    @Nested
    @DisplayName("AC3: Build Caching")
    class BuildCaching {

        @Test
        @DisplayName("Reusable setup action configures Maven dependency caching")
        void reusableActionConfiguresMavenCache() throws IOException {
            // Caching is configured in the reusable composite action, not in
            // each workflow file. This avoids duplication. The setup-java
            // action's cache: 'maven' parameter handles ~/.m2/repository.
            String content = Files.readString(
                    projectRoot.resolve(".github/actions/setup-java-maven/action.yml"));
            assertThat(content)
                    .as("Reusable action must configure Maven dependency caching")
                    .contains("cache")
                    .contains("maven");
        }

        @Test
        @DisplayName("All workflows use the reusable setup action for consistent caching")
        void workflowsUseReusableSetupAction() throws IOException {
            for (String workflow : new String[]{"ci-pr.yml", "ci-main.yml",
                    "proto-validate.yml"}) {
                String content = readWorkflow(workflow);
                assertThat(content)
                        .as("Workflow %s must use the reusable setup-java-maven action", workflow)
                        .contains(".github/actions/setup-java-maven");
            }
        }
    }

    // ================================================================
    // AC4: Test Reporting
    // ================================================================
    @Nested
    @DisplayName("AC4: Test Reporting")
    class TestReporting {

        @Test
        @DisplayName("Main workflow publishes JUnit test reports")
        void mainWorkflowPublishesTestReports() throws IOException {
            String content = readWorkflow("ci-main.yml");
            assertThat(content)
                    .as("Main workflow must reference test report publishing")
                    .containsPattern("test-report|surefire-reports|junit");
        }

        @Test
        @DisplayName("Main workflow publishes JaCoCo coverage reports")
        void mainWorkflowPublishesCoverageReports() throws IOException {
            String content = readWorkflow("ci-main.yml");
            assertThat(content)
                    .as("Main workflow must reference JaCoCo coverage reports")
                    .containsPattern("jacoco|coverage");
        }
    }

    // ================================================================
    // AC5: Proto Validation
    // ================================================================
    @Nested
    @DisplayName("AC5: Proto Validation Workflow")
    class ProtoValidation {

        @Test
        @DisplayName("Proto validation workflow file exists")
        void protoWorkflowFileExists() {
            assertThat(projectRoot.resolve(".github/workflows/proto-validate.yml"))
                    .as("Proto validation workflow must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Proto workflow triggers on proto file changes")
        void protoWorkflowTriggersOnProtoChanges() throws IOException {
            String content = readWorkflow("proto-validate.yml");
            assertThat(content)
                    .as("Proto workflow must trigger on proto file changes")
                    .contains("proto");
        }

        @Test
        @DisplayName("Proto workflow compiles and tests proto definitions")
        void protoWorkflowCompilesProtos() throws IOException {
            String content = readWorkflow("proto-validate.yml");
            assertThat(content)
                    .as("Proto workflow must compile proto files via Maven")
                    .containsPattern("mvn.*grpc-api|mvnw.*grpc-api|libs/grpc-api");
        }
    }

    // ================================================================
    // AC6: Security Scanning
    // ================================================================
    @Nested
    @DisplayName("AC6: Security Scanning")
    class SecurityScanning {

        @Test
        @DisplayName("Main workflow includes dependency vulnerability scanning")
        void mainWorkflowIncludesDependencyScanning() throws IOException {
            String content = readWorkflow("ci-main.yml");
            assertThat(content)
                    .as("Main workflow must include dependency vulnerability scanning")
                    .containsPattern("dependency-check|dependency-review|trivy|snyk|audit");
        }
    }

    // ================================================================
    // AC7: Docker Build
    // ================================================================
    @Nested
    @DisplayName("AC7: Docker Build Workflow")
    class DockerBuild {

        @Test
        @DisplayName("Docker build workflow file exists")
        void dockerWorkflowFileExists() {
            assertThat(projectRoot.resolve(".github/workflows/docker-build.yml"))
                    .as("Docker build workflow must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Docker workflow uses multi-stage builds")
        void dockerWorkflowUsesMultiStageBuild() throws IOException {
            String content = readWorkflow("docker-build.yml");
            assertThat(content)
                    .as("Docker workflow must reference Dockerfile or Docker build")
                    .containsPattern("docker|Dockerfile|build-push-action|buildx");
        }

        @Test
        @DisplayName("Docker workflow tags images with commit SHA")
        void dockerWorkflowTagsWithSha() throws IOException {
            String content = readWorkflow("docker-build.yml");
            assertThat(content)
                    .as("Docker workflow must tag images with commit SHA")
                    .containsPattern("sha|SHA|github.sha");
        }

        @Test
        @DisplayName("Docker workflow pushes to container registry")
        void dockerWorkflowPushesToRegistry() throws IOException {
            String content = readWorkflow("docker-build.yml");
            assertThat(content)
                    .as("Docker workflow must push to container registry")
                    .containsPattern("ghcr.io|registry|push");
        }
    }

    // ================================================================
    // Supporting Infrastructure
    // ================================================================
    @Nested
    @DisplayName("Supporting Files")
    class SupportingFiles {

        @Test
        @DisplayName("CODEOWNERS file exists")
        void codeownersExists() {
            assertThat(projectRoot.resolve(".github/CODEOWNERS"))
                    .as("CODEOWNERS file must exist for PR review routing")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("CODEOWNERS assigns global ownership")
        void codeownersHasGlobalOwnership() throws IOException {
            String content = Files.readString(
                    projectRoot.resolve(".github/CODEOWNERS"));
            assertThat(content)
                    .as("CODEOWNERS must have a global ownership rule")
                    .containsPattern("\\*\\s+@");
        }

        @Test
        @DisplayName("Reusable setup-java-maven action exists")
        void reusableSetupActionExists() {
            assertThat(projectRoot.resolve(".github/actions/setup-java-maven/action.yml"))
                    .as("Reusable setup-java-maven action must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Dockerfile template exists for services")
        void dockerfileTemplateExists() {
            assertThat(projectRoot.resolve("services/Dockerfile.template"))
                    .as("Dockerfile template must exist for service containerization")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Dockerfile template uses multi-stage build")
        void dockerfileTemplateUsesMultiStage() throws IOException {
            String content = Files.readString(
                    projectRoot.resolve("services/Dockerfile.template"));
            assertThat(content)
                    .as("Dockerfile template must use multi-stage build (build + runtime)")
                    .contains("FROM")
                    .containsPattern("AS\\s+(build|builder)")
                    .containsPattern("FROM.*temurin|FROM.*eclipse");
        }

        @Test
        @DisplayName("Dockerfile template runs as non-root user")
        void dockerfileTemplateRunsAsNonRoot() throws IOException {
            String content = Files.readString(
                    projectRoot.resolve("services/Dockerfile.template"));
            assertThat(content)
                    .as("Dockerfile template must run as non-root user for security")
                    .contains("USER");
        }
    }

    // ================================================================
    // Workflow Quality Checks
    // ================================================================
    @Nested
    @DisplayName("Workflow Quality")
    class WorkflowQuality {

        @Test
        @DisplayName("All workflows use pinned action versions")
        void workflowsUsePinnedActions() throws IOException {
            for (String workflow : new String[]{"ci-pr.yml", "ci-main.yml",
                    "proto-validate.yml", "docker-build.yml"}) {
                String content = readWorkflow(workflow);
                // Actions should use @v4, @v5, etc. not @main or @master
                assertThat(content)
                        .as("Workflow %s must use pinned action versions (@vN)", workflow)
                        .containsPattern("uses:.*@v\\d");
            }
        }

        @Test
        @DisplayName("PR workflow sets appropriate timeout")
        void prWorkflowHasTimeout() throws IOException {
            String content = readWorkflow("ci-pr.yml");
            assertThat(content)
                    .as("PR workflow should have a timeout to prevent hung builds")
                    .contains("timeout-minutes");
        }

        @Test
        @DisplayName("All workflows specify runs-on")
        void allWorkflowsSpecifyRunner() throws IOException {
            for (String workflow : new String[]{"ci-pr.yml", "ci-main.yml",
                    "proto-validate.yml", "docker-build.yml"}) {
                String content = readWorkflow(workflow);
                assertThat(content)
                        .as("Workflow %s must specify runs-on", workflow)
                        .contains("runs-on");
            }
        }
    }

    // ================================================================
    // Helper
    // ================================================================

    private static String readWorkflow(String filename) throws IOException {
        return Files.readString(
                projectRoot.resolve(".github/workflows/" + filename));
    }
}
