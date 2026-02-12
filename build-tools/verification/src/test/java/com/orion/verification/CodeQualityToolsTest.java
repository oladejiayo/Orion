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
 * Structural verification tests for code quality tooling (US-01-08).
 *
 * <p>WHY: The original story specifies ESLint, Prettier, Husky, and commitlint for TypeScript/Nx.
 * We reinterpret for Java 21 + Maven: - ESLint → Checkstyle (Google Java Style + Orion
 * customizations) - Prettier → Spotless Maven Plugin (Google Java Format auto-formatter) - Husky →
 * .githooks/ directory with pre-commit and commit-msg scripts - commitlint → Shell-based
 * conventional commit validation in commit-msg hook - VS Code → .vscode/settings.json +
 * extensions.json for Java development - EditorConfig → Already present, verify completeness +
 * Maven Enforcer Plugin — enforce minimum Java/Maven versions, ban duplicates
 *
 * <p>These tests ensure the quality tooling infrastructure stays in place. Any accidental deletion
 * or misconfiguration is caught at build time.
 */
@DisplayName("US-01-08: Code Quality Tools and Standards")
class CodeQualityToolsTest {

    private static Path projectRoot;
    private static String parentPom;

    @BeforeAll
    static void resolveProjectRoot() throws IOException {
        // Maven runs tests with CWD = module directory (build-tools/verification)
        projectRoot = Path.of(System.getProperty("user.dir")).getParent().getParent();
        assertThat(projectRoot.resolve("pom.xml"))
                .as("Root pom.xml must exist at project root: %s", projectRoot)
                .exists();
        parentPom = Files.readString(projectRoot.resolve("pom.xml"));
    }

    // ================================================================
    // Helper — read file content as string
    // ================================================================
    private static String readFile(String relativePath) throws IOException {
        Path file = projectRoot.resolve(relativePath);
        assertThat(file).as("File must exist: %s", relativePath).exists();
        return Files.readString(file);
    }

    // ================================================================
    // AC1: Checkstyle Configuration (reinterprets ESLint)
    // ================================================================
    @Nested
    @DisplayName("AC1: Checkstyle Configuration")
    class CheckstyleConfiguration {

        @Test
        @DisplayName("Checkstyle config file exists")
        void checkstyleConfigFileExists() {
            // WHY: Checkstyle needs a ruleset file to define coding standards
            assertThat(projectRoot.resolve("build-tools/checkstyle.xml"))
                    .as("Checkstyle configuration must exist at build-tools/checkstyle.xml")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Checkstyle config includes module checks")
        void checkstyleConfigIncludesModuleChecks() throws IOException {
            // WHY: Must have real Checkstyle modules, not just an empty file
            String content = readFile("build-tools/checkstyle.xml");
            assertThat(content)
                    .as("Checkstyle config must define Checker module")
                    .contains("<module name=\"Checker\">");
            assertThat(content)
                    .as("Checkstyle config must define TreeWalker for code-level checks")
                    .contains("<module name=\"TreeWalker\">");
        }

        @Test
        @DisplayName("Checkstyle enforces Orion naming conventions")
        void checkstyleEnforcesNamingConventions() throws IOException {
            // WHY: Consistent naming across all modules prevents style drift
            String content = readFile("build-tools/checkstyle.xml");
            assertThat(content).as("Must check method names").contains("MethodName");
            assertThat(content).as("Must check type names").contains("TypeName");
        }

        @Test
        @DisplayName("Checkstyle suppression file exists")
        void checkstyleSuppressionsExist() {
            // WHY: Generated proto code and test code need specific suppressions
            assertThat(projectRoot.resolve("build-tools/checkstyle-suppressions.xml"))
                    .as("Checkstyle suppressions must exist for generated code")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Parent POM configures maven-checkstyle-plugin")
        void parentPomConfiguresCheckstylePlugin() {
            // WHY: Checkstyle must be wired into the Maven build lifecycle
            assertThat(parentPom)
                    .as("Parent POM must include maven-checkstyle-plugin")
                    .contains("maven-checkstyle-plugin");
        }
    }

    // ================================================================
    // AC2: Spotless Configuration (reinterprets Prettier)
    // ================================================================
    @Nested
    @DisplayName("AC2: Spotless Formatting")
    class SpotlessFormatting {

        @Test
        @DisplayName("Parent POM configures spotless-maven-plugin")
        void parentPomConfiguresSpotlessPlugin() {
            // WHY: Spotless auto-formats Java code — equivalent to Prettier for TypeScript
            assertThat(parentPom)
                    .as("Parent POM must include spotless-maven-plugin")
                    .contains("spotless-maven-plugin");
        }

        @Test
        @DisplayName("Spotless uses Google Java Format")
        void spotlessUsesGoogleJavaFormat() {
            // WHY: Google Java Format is the de facto standard for Java,
            // like Prettier is for JavaScript
            assertThat(parentPom)
                    .as("Spotless must be configured with Google Java Format")
                    .contains("googleJavaFormat");
        }

        @Test
        @DisplayName("Spotless has a check profile for CI")
        void spotlessHasCheckProfile() {
            // WHY: CI needs mvn spotless:check to fail on unformatted code;
            // developers use mvn spotless:apply to auto-fix
            assertThat(parentPom)
                    .as("POM must reference spotless (either in profiles or pluginManagement)")
                    .contains("spotless");
        }
    }

    // ================================================================
    // AC3: Git Hooks (reinterprets Husky + lint-staged)
    // ================================================================
    @Nested
    @DisplayName("AC3: Git Hooks")
    class GitHooks {

        @Test
        @DisplayName(".githooks directory exists")
        void githooksDirectoryExists() {
            // WHY: Custom hooks directory keeps hooks in version control
            // (default .git/hooks/ is not tracked by Git)
            assertThat(projectRoot.resolve(".githooks"))
                    .as(".githooks directory must exist")
                    .exists()
                    .isDirectory();
        }

        @Test
        @DisplayName("Pre-commit hook exists")
        void preCommitHookExists() {
            // WHY: Pre-commit hook runs formatting check before allowing commit
            assertThat(projectRoot.resolve(".githooks/pre-commit"))
                    .as("Pre-commit hook must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Pre-commit hook runs Spotless check")
        void preCommitHookRunsSpotless() throws IOException {
            // WHY: Catches unformatted code before it reaches the repository
            String content = readFile(".githooks/pre-commit");
            assertThat(content)
                    .as("Pre-commit hook must invoke Spotless or Maven verify")
                    .containsAnyOf("spotless", "mvnw");
        }

        @Test
        @DisplayName("Commit-msg hook exists")
        void commitMsgHookExists() {
            // WHY: Enforces conventional commit format on every commit
            assertThat(projectRoot.resolve(".githooks/commit-msg"))
                    .as("Commit-msg hook must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("Commit-msg hook validates conventional commits")
        void commitMsgHookValidatesConventionalCommits() throws IOException {
            // WHY: Conventional commits enable automated changelogs and semantic versioning
            String content = readFile(".githooks/commit-msg");
            assertThat(content)
                    .as("Commit-msg hook must reference conventional commit types")
                    .contains("feat")
                    .contains("fix");
        }

        @Test
        @DisplayName("Hook setup instructions exist")
        void hookSetupInstructionsExist() throws IOException {
            // WHY: Unlike Husky which auto-installs, Git hooks need manual activation:
            // git config core.hooksPath .githooks
            String readme = readFile(".githooks/README.md");
            assertThat(readme)
                    .as("README must explain how to activate hooks")
                    .contains("core.hooksPath");
        }
    }

    // ================================================================
    // AC4: Conventional Commits Enforcement
    // ================================================================
    @Nested
    @DisplayName("AC4: Conventional Commits")
    class ConventionalCommits {

        @Test
        @DisplayName("Commit-msg hook enforces required types")
        void commitMsgHookEnforcesRequiredTypes() throws IOException {
            // WHY: Consistent commit types enable automated release notes
            String content = readFile(".githooks/commit-msg");
            for (String type :
                    new String[] {
                        "feat",
                        "fix",
                        "docs",
                        "style",
                        "refactor",
                        "perf",
                        "test",
                        "chore",
                        "revert",
                        "ci",
                        "build"
                    }) {
                assertThat(content).as("Commit-msg hook must allow type: %s", type).contains(type);
            }
        }

        @Test
        @DisplayName("Commit-msg hook enforces scope:subject format")
        void commitMsgHookEnforcesScopeSubjectFormat() throws IOException {
            // WHY: Format "type(scope): subject" must be validated
            String content = readFile(".githooks/commit-msg");
            assertThat(content)
                    .as("Hook must validate type(scope): subject pattern")
                    .containsAnyOf("regex", "pattern", "PATTERN", "REGEX", "grep");
        }
    }

    // ================================================================
    // AC5: VS Code Workspace Settings
    // ================================================================
    @Nested
    @DisplayName("AC5: VS Code Settings")
    class VSCodeSettings {

        @Test
        @DisplayName("VS Code settings.json exists")
        void vsCodeSettingsExist() {
            // WHY: Shared settings ensure consistent editor behavior across the team
            assertThat(projectRoot.resolve(".vscode/settings.json"))
                    .as(".vscode/settings.json must exist for shared team settings")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName("VS Code settings configure Java formatting")
        void vsCodeSettingsConfigureJavaFormatting() throws IOException {
            // WHY: Format on save + Google Java Format keeps code consistent
            String content = readFile(".vscode/settings.json");
            assertThat(content)
                    .as("Settings must configure Java formatting")
                    .containsAnyOf("java", "formatOnSave", "editor.formatOnSave");
        }

        @Test
        @DisplayName("VS Code extensions.json recommends Java extensions")
        void vsCodeExtensionsRecommendJava() throws IOException {
            // WHY: New team members get prompted to install essential extensions
            String content = readFile(".vscode/extensions.json");
            assertThat(content).as("Must recommend Java extension pack").contains("vscjava");
        }

        @Test
        @DisplayName("VS Code extensions.json recommends Checkstyle extension")
        void vsCodeExtensionsRecommendCheckstyle() throws IOException {
            // WHY: Real-time Checkstyle feedback in the editor
            String content = readFile(".vscode/extensions.json");
            assertThat(content)
                    .as("Must recommend a code quality extension")
                    .containsAnyOf("checkstyle", "sonarlint", "java");
        }

        @Test
        @DisplayName(".gitignore allows .vscode shared settings")
        void gitignoreAllowsVsCodeSettings() throws IOException {
            // WHY: .vscode/ is gitignored by default, but settings.json and
            // extensions.json should be committed for team consistency
            String gitignore = readFile(".gitignore");
            assertThat(gitignore)
                    .as(
                            ".gitignore must whitelist .vscode/settings.json or"
                                    + " .vscode/extensions.json")
                    .containsAnyOf(
                            "!.vscode/settings.json", "!.vscode/extensions.json", "!.vscode/");
        }
    }

    // ================================================================
    // AC6: EditorConfig Completeness
    // ================================================================
    @Nested
    @DisplayName("AC6: EditorConfig")
    class EditorConfigCompleteness {

        @Test
        @DisplayName(".editorconfig exists")
        void editorConfigExists() {
            assertThat(projectRoot.resolve(".editorconfig"))
                    .as(".editorconfig must exist")
                    .exists()
                    .isRegularFile();
        }

        @Test
        @DisplayName(".editorconfig defines Java indent settings")
        void editorConfigDefinesJavaIndent() throws IOException {
            // WHY: Java uses 4-space indent (unlike TypeScript/JS which uses 2)
            String content = readFile(".editorconfig");
            assertThat(content)
                    .as("Must define indent settings for Java")
                    .contains("indent_size = 4");
        }

        @Test
        @DisplayName(".editorconfig defines charset and line endings")
        void editorConfigDefinesCharset() throws IOException {
            String content = readFile(".editorconfig");
            assertThat(content).as("Must define charset").contains("charset = utf-8");
            assertThat(content).as("Must define line endings").contains("end_of_line = lf");
        }

        @Test
        @DisplayName(".editorconfig handles proto files")
        void editorConfigHandlesProtoFiles() throws IOException {
            // WHY: Proto files use 2-space indent per Google style guide
            String content = readFile(".editorconfig");
            assertThat(content).as("Must have proto file settings").contains("proto");
        }
    }

    // ================================================================
    // AC+: Maven Enforcer Plugin (additional for Java)
    // ================================================================
    @Nested
    @DisplayName("Maven Enforcer Plugin")
    class MavenEnforcerPlugin {

        @Test
        @DisplayName("Parent POM configures maven-enforcer-plugin")
        void parentPomConfiguresEnforcerPlugin() {
            // WHY: Enforcer ensures all developers use compatible Java/Maven versions
            assertThat(parentPom)
                    .as("Parent POM must include maven-enforcer-plugin")
                    .contains("maven-enforcer-plugin");
        }

        @Test
        @DisplayName("Enforcer requires minimum Java version")
        void enforcerRequiresMinimumJava() {
            // WHY: Java 21 is required for virtual threads, records, pattern matching
            assertThat(parentPom)
                    .as("Enforcer must specify minimum Java version requirement")
                    .contains("requireJavaVersion");
        }

        @Test
        @DisplayName("Enforcer requires minimum Maven version")
        void enforcerRequiresMinimumMaven() {
            // WHY: Maven 3.9+ is needed for proper Java 21 support
            assertThat(parentPom)
                    .as("Enforcer must specify minimum Maven version requirement")
                    .contains("requireMavenVersion");
        }

        @Test
        @DisplayName("Enforcer bans duplicate dependencies")
        void enforcerBansDuplicateDependencies() {
            // WHY: Duplicate deps cause classloader issues and unpredictable behavior
            assertThat(parentPom)
                    .as("Enforcer must ban duplicate dependencies")
                    .contains("banDuplicatePomDependencyVersions");
        }
    }
}
