# US-17-06: Quality Gates & CI Integration

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-17-06 |
| **Epic** | Epic 17: Testing & Quality |
| **Title** | Quality Gates & CI Integration |
| **Priority** | High |
| **Story Points** | 5 |
| **Status** | Ready for Development |

## User Story

**As a** engineering manager  
**I want** automated quality gates in our CI/CD pipeline  
**So that** only quality code reaches production

## Acceptance Criteria

### AC1: SonarQube Integration
- **Given** code commits
- **When** analysis runs
- **Then**:
  - Code coverage reported
  - Code smells detected
  - Security vulnerabilities scanned
  - Duplications identified

### AC2: Quality Gate Definition
- **Given** quality standards
- **When** gate evaluated
- **Then**:
  - Coverage threshold enforced
  - No new critical issues
  - No new security hotspots
  - Technical debt ratio

### AC3: CI Pipeline Integration
- **Given** GitHub Actions
- **When** PR created
- **Then**:
  - All tests pass
  - Quality gate passes
  - Security scan passes
  - PR blocked if failed

### AC4: Coverage Enforcement
- **Given** code changes
- **When** coverage calculated
- **Then**:
  - Line coverage ≥ 80%
  - Branch coverage ≥ 70%
  - New code coverage ≥ 90%
  - Incremental reporting

### AC5: Dependency Security
- **Given** project dependencies
- **When** security scan runs
- **Then**:
  - CVE detection
  - License compliance
  - Outdated dependencies
  - Automated updates

## Technical Specification

### SonarQube Configuration

```properties
# sonar-project.properties
sonar.projectKey=orion-platform
sonar.projectName=Orion Liquidity Platform
sonar.projectVersion=1.0.0

sonar.sources=src/main/java
sonar.tests=src/test/java
sonar.java.binaries=target/classes
sonar.java.test.binaries=target/test-classes

# Coverage
sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
sonar.junit.reportPaths=target/surefire-reports

# Exclusions
sonar.exclusions=**/generated/**,**/config/**,**/*Config.java,**/*Application.java
sonar.coverage.exclusions=**/dto/**,**/config/**,**/*Exception.java

# Quality Gate
sonar.qualitygate.wait=true

# Pull Request Analysis
sonar.pullrequest.key=${GITHUB_PR_NUMBER}
sonar.pullrequest.branch=${GITHUB_HEAD_REF}
sonar.pullrequest.base=${GITHUB_BASE_REF}
sonar.pullrequest.provider=github
sonar.pullrequest.github.repository=${GITHUB_REPOSITORY}
```

### Quality Gate Definition

```json
{
  "name": "Orion Quality Gate",
  "conditions": [
    {
      "metric": "new_reliability_rating",
      "op": "GT",
      "error": "1"
    },
    {
      "metric": "new_security_rating",
      "op": "GT",
      "error": "1"
    },
    {
      "metric": "new_maintainability_rating",
      "op": "GT",
      "error": "1"
    },
    {
      "metric": "new_coverage",
      "op": "LT",
      "error": "80"
    },
    {
      "metric": "new_duplicated_lines_density",
      "op": "GT",
      "error": "3"
    },
    {
      "metric": "new_security_hotspots_reviewed",
      "op": "LT",
      "error": "100"
    },
    {
      "metric": "new_blocker_violations",
      "op": "GT",
      "error": "0"
    },
    {
      "metric": "new_critical_violations",
      "op": "GT",
      "error": "0"
    }
  ]
}
```

### Maven Build with Quality

```xml
<!-- pom.xml - Quality plugins -->
<build>
    <plugins>
        <!-- JaCoCo Coverage -->
        <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>0.8.11</version>
            <executions>
                <execution>
                    <id>prepare-agent</id>
                    <goals>
                        <goal>prepare-agent</goal>
                    </goals>
                </execution>
                <execution>
                    <id>report</id>
                    <phase>test</phase>
                    <goals>
                        <goal>report</goal>
                    </goals>
                </execution>
                <execution>
                    <id>check</id>
                    <goals>
                        <goal>check</goal>
                    </goals>
                    <configuration>
                        <rules>
                            <rule>
                                <element>BUNDLE</element>
                                <limits>
                                    <limit>
                                        <counter>LINE</counter>
                                        <value>COVEREDRATIO</value>
                                        <minimum>0.80</minimum>
                                    </limit>
                                    <limit>
                                        <counter>BRANCH</counter>
                                        <value>COVEREDRATIO</value>
                                        <minimum>0.70</minimum>
                                    </limit>
                                </limits>
                            </rule>
                            <rule>
                                <element>CLASS</element>
                                <limits>
                                    <limit>
                                        <counter>LINE</counter>
                                        <value>COVEREDRATIO</value>
                                        <minimum>0.60</minimum>
                                    </limit>
                                </limits>
                                <excludes>
                                    <exclude>*Config</exclude>
                                    <exclude>*Application</exclude>
                                    <exclude>*Exception</exclude>
                                </excludes>
                            </rule>
                        </rules>
                    </configuration>
                </execution>
            </executions>
        </plugin>

        <!-- SpotBugs -->
        <plugin>
            <groupId>com.github.spotbugs</groupId>
            <artifactId>spotbugs-maven-plugin</artifactId>
            <version>4.8.3.0</version>
            <configuration>
                <effort>Max</effort>
                <threshold>Medium</threshold>
                <failOnError>true</failOnError>
                <plugins>
                    <plugin>
                        <groupId>com.h3xstream.findsecbugs</groupId>
                        <artifactId>findsecbugs-plugin</artifactId>
                        <version>1.12.0</version>
                    </plugin>
                </plugins>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>check</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>

        <!-- Checkstyle -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-checkstyle-plugin</artifactId>
            <version>3.3.1</version>
            <configuration>
                <configLocation>checkstyle.xml</configLocation>
                <consoleOutput>true</consoleOutput>
                <failsOnError>true</failsOnError>
                <linkXRef>false</linkXRef>
            </configuration>
            <executions>
                <execution>
                    <id>validate</id>
                    <phase>validate</phase>
                    <goals>
                        <goal>check</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>

        <!-- OWASP Dependency Check -->
        <plugin>
            <groupId>org.owasp</groupId>
            <artifactId>dependency-check-maven</artifactId>
            <version>9.0.9</version>
            <configuration>
                <failBuildOnCVSS>7</failBuildOnCVSS>
                <suppressionFiles>
                    <suppressionFile>dependency-check-suppressions.xml</suppressionFile>
                </suppressionFiles>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>check</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### GitHub Actions CI Pipeline

```yaml
# .github/workflows/ci.yml
name: CI Pipeline

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

permissions:
  contents: read
  pull-requests: write
  checks: write

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_DB: orion_test
          POSTGRES_USER: test
          POSTGRES_PASSWORD: test
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0  # Full history for SonarQube

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Cache SonarQube packages
        uses: actions/cache@v4
        with:
          path: ~/.sonar/cache
          key: ${{ runner.os }}-sonar
          restore-keys: ${{ runner.os }}-sonar

      - name: Build with Maven
        run: |
          mvn clean verify \
            -Dspring.profiles.active=test \
            -Dspring.datasource.url=jdbc:postgresql://localhost:5432/orion_test

      - name: SonarQube Analysis
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
          GITHUB_PR_NUMBER: ${{ github.event.pull_request.number }}
          GITHUB_HEAD_REF: ${{ github.head_ref }}
          GITHUB_BASE_REF: ${{ github.base_ref }}
        run: |
          mvn sonar:sonar \
            -Dsonar.host.url=${{ vars.SONAR_HOST_URL }} \
            -Dsonar.token=${{ secrets.SONAR_TOKEN }}

      - name: Upload Coverage Report
        uses: actions/upload-artifact@v4
        with:
          name: coverage-report
          path: target/site/jacoco/

      - name: Publish Test Results
        uses: EnricoMi/publish-unit-test-result-action@v2
        if: always()
        with:
          files: |
            **/target/surefire-reports/*.xml
            **/target/failsafe-reports/*.xml

  security-scan:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: OWASP Dependency Check
        run: |
          mvn dependency-check:check \
            -DfailBuildOnCVSS=7 \
            -Dformat=ALL

      - name: Upload Dependency Check Report
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: dependency-check-report
          path: target/dependency-check-report.*

      - name: Run Trivy vulnerability scanner
        uses: aquasecurity/trivy-action@master
        with:
          scan-type: 'fs'
          scan-ref: '.'
          format: 'sarif'
          output: 'trivy-results.sarif'

      - name: Upload Trivy scan results
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: 'trivy-results.sarif'

  lint-and-format:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'

      - name: Checkstyle
        run: mvn checkstyle:check

      - name: SpotBugs
        run: mvn spotbugs:check

      - name: PMD
        run: mvn pmd:check

  quality-gate:
    needs: [build-and-test, security-scan, lint-and-format]
    runs-on: ubuntu-latest
    if: github.event_name == 'pull_request'
    
    steps:
      - name: Check Quality Gate
        uses: sonarsource/sonarqube-quality-gate-action@master
        timeout-minutes: 5
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        with:
          scanMetadataReportFile: target/sonar/report-task.txt

      - name: Comment PR with Quality Status
        uses: actions/github-script@v7
        with:
          script: |
            const { data: comments } = await github.rest.issues.listComments({
              owner: context.repo.owner,
              repo: context.repo.repo,
              issue_number: context.issue.number,
            });
            
            const botComment = comments.find(comment => 
              comment.user.type === 'Bot' && 
              comment.body.includes('Quality Gate')
            );
            
            const body = `## Quality Gate Status ✅
            
            | Metric | Status |
            |--------|--------|
            | Coverage | ≥ 80% |
            | Duplications | ≤ 3% |
            | Security | No new vulnerabilities |
            | Reliability | No new bugs |
            
            [View detailed report](${{ vars.SONAR_HOST_URL }}/dashboard?id=orion-platform&pullRequest=${{ github.event.pull_request.number }})
            `;
            
            if (botComment) {
              await github.rest.issues.updateComment({
                owner: context.repo.owner,
                repo: context.repo.repo,
                comment_id: botComment.id,
                body
              });
            } else {
              await github.rest.issues.createComment({
                owner: context.repo.owner,
                repo: context.repo.repo,
                issue_number: context.issue.number,
                body
              });
            }
```

### Frontend Quality Configuration

```javascript
// vitest.config.ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{js,jsx,ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov', 'json'],
      reportsDirectory: './coverage',
      exclude: [
        'node_modules/',
        'src/test/',
        '**/*.d.ts',
        '**/*.config.*',
        '**/index.ts',
      ],
      thresholds: {
        global: {
          branches: 70,
          functions: 80,
          lines: 80,
          statements: 80,
        },
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
});
```

```json
// .eslintrc.json
{
  "root": true,
  "env": {
    "browser": true,
    "es2022": true,
    "node": true
  },
  "extends": [
    "eslint:recommended",
    "plugin:@typescript-eslint/recommended",
    "plugin:@typescript-eslint/recommended-requiring-type-checking",
    "plugin:react/recommended",
    "plugin:react-hooks/recommended",
    "plugin:jsx-a11y/recommended",
    "plugin:import/recommended",
    "plugin:import/typescript",
    "plugin:sonarjs/recommended",
    "plugin:security/recommended",
    "prettier"
  ],
  "parser": "@typescript-eslint/parser",
  "parserOptions": {
    "ecmaVersion": "latest",
    "sourceType": "module",
    "project": ["./tsconfig.json"],
    "ecmaFeatures": {
      "jsx": true
    }
  },
  "plugins": [
    "@typescript-eslint",
    "react",
    "react-hooks",
    "jsx-a11y",
    "import",
    "sonarjs",
    "security"
  ],
  "rules": {
    "@typescript-eslint/no-unused-vars": ["error", { "argsIgnorePattern": "^_" }],
    "@typescript-eslint/explicit-function-return-type": "off",
    "@typescript-eslint/explicit-module-boundary-types": "off",
    "@typescript-eslint/no-explicit-any": "warn",
    "react/react-in-jsx-scope": "off",
    "react/prop-types": "off",
    "import/order": [
      "error",
      {
        "groups": ["builtin", "external", "internal", "parent", "sibling", "index"],
        "newlines-between": "always",
        "alphabetize": { "order": "asc" }
      }
    ],
    "sonarjs/cognitive-complexity": ["error", 15],
    "sonarjs/no-duplicate-string": ["error", 3]
  },
  "settings": {
    "react": {
      "version": "detect"
    },
    "import/resolver": {
      "typescript": {
        "alwaysTryTypes": true
      }
    }
  }
}
```

### Dependabot Configuration

```yaml
# .github/dependabot.yml
version: 2
updates:
  # Backend Java dependencies
  - package-ecosystem: "maven"
    directory: "/"
    schedule:
      interval: "weekly"
      day: "monday"
      time: "06:00"
    open-pull-requests-limit: 10
    groups:
      spring:
        patterns:
          - "org.springframework*"
      testing:
        patterns:
          - "org.junit*"
          - "org.mockito*"
          - "org.assertj*"
          - "org.testcontainers*"
    ignore:
      - dependency-name: "*"
        update-types: ["version-update:semver-major"]
    labels:
      - "dependencies"
      - "java"
    reviewers:
      - "backend-team"

  # Frontend npm dependencies
  - package-ecosystem: "npm"
    directory: "/frontend"
    schedule:
      interval: "weekly"
      day: "monday"
      time: "06:00"
    open-pull-requests-limit: 10
    groups:
      react:
        patterns:
          - "react*"
          - "@types/react*"
      tanstack:
        patterns:
          - "@tanstack/*"
      testing:
        patterns:
          - "vitest*"
          - "@testing-library/*"
          - "playwright*"
    labels:
      - "dependencies"
      - "frontend"
    reviewers:
      - "frontend-team"

  # GitHub Actions
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
    labels:
      - "dependencies"
      - "ci"

  # Docker images
  - package-ecosystem: "docker"
    directory: "/"
    schedule:
      interval: "weekly"
    labels:
      - "dependencies"
      - "docker"
```

### Pre-commit Hooks

```yaml
# .pre-commit-config.yaml
repos:
  - repo: https://github.com/pre-commit/pre-commit-hooks
    rev: v4.5.0
    hooks:
      - id: trailing-whitespace
      - id: end-of-file-fixer
      - id: check-yaml
      - id: check-json
      - id: check-added-large-files
        args: ['--maxkb=1000']
      - id: check-merge-conflict
      - id: detect-private-key

  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.18.1
    hooks:
      - id: gitleaks

  - repo: local
    hooks:
      - id: java-checkstyle
        name: Checkstyle
        entry: mvn checkstyle:check -q
        language: system
        files: \.java$
        pass_filenames: false

      - id: frontend-lint
        name: ESLint
        entry: bash -c 'cd frontend && npm run lint'
        language: system
        files: \.(ts|tsx)$
        pass_filenames: false

      - id: frontend-typecheck
        name: TypeScript Check
        entry: bash -c 'cd frontend && npm run typecheck'
        language: system
        files: \.(ts|tsx)$
        pass_filenames: false
```

## Definition of Done

- [ ] SonarQube configured
- [ ] Quality gate defined
- [ ] JaCoCo coverage enforcement
- [ ] SpotBugs/Checkstyle integrated
- [ ] OWASP dependency check
- [ ] Dependabot configured
- [ ] Pre-commit hooks setup
- [ ] CI pipeline complete
- [ ] PR blocking on failure
- [ ] Documentation complete

## Test Cases

```yaml
test-cases:
  - name: "Quality gate blocks PR"
    given: "Code with coverage < 80%"
    when: "PR created"
    then: "Quality gate fails, PR cannot merge"
  
  - name: "Security vulnerability blocks build"
    given: "Dependency with high CVE"
    when: "Build runs"
    then: "Build fails with security report"
  
  - name: "Incremental coverage check"
    given: "New code added"
    when: "Coverage analyzed"
    then: "New code coverage ≥ 90%"
```
