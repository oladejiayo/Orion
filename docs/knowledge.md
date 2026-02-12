# Orion Platform — Knowledge Base

> Living document of business context, technical decisions, and domain knowledge accumulated during implementation.

---

## US-01-01: Initialize Monorepo with Maven Multi-Module Build

### Business Context
Orion is an institutional multi-asset liquidity & data platform. Before any services can be built, we need the **project skeleton** — the Maven multi-module monorepo that will host all backend microservices, shared libraries, frontend applications, and infrastructure code.

### Why Maven Multi-Module?
The PRD specifies Java 21 + Spring Boot 3.x for all backend services. Maven multi-module is the standard approach for Java monorepos:
- **Parent POM** defines shared dependency versions (Spring Boot BOM, test libraries, plugins)
- **Child modules** inherit from parent, reducing duplication
- **Reactor build** ensures correct build order based on inter-module dependencies
- **`mvn verify`** from root builds and tests everything

### Reinterpretation of US-01-01
The original user story was written with Nx/TypeScript tooling in mind. We reinterpret each acceptance criterion for the Java/Maven stack:

| Original (Nx/TS) | Reinterpreted (Java/Maven) |
|---|---|
| Root `package.json` | Root `pom.xml` (parent POM) |
| Nx installed and configured | Maven wrapper (`mvnw`) included |
| `nx.json` configuration | Maven `pom.xml` with module declarations |
| `tsconfig.base.json` | Java compiler settings in parent POM (`maven-compiler-plugin`) |
| `nx build <project>` | `mvn package -pl services/bff-workstation` |
| `nx test <project>` | `mvn test -pl services/bff-workstation` |
| `nx affected:build` | `mvn package` (Maven reactor handles dependencies) |
| `nx graph` | `mvn dependency:tree` / project structure |
| Path aliases `@orion/event-model` | Maven dependency `<groupId>com.orion</groupId>` |
| TypeScript strict mode | Java compiler `-parameters`, `--enable-preview` flags |

### Acceptance Criteria Mapping

| AC | Description | Java/Maven Equivalent |
|---|---|---|
| AC1 | Repository root structure | Parent POM, Maven wrapper, `.gitignore`, `.editorconfig` |
| AC2 | Workspace configuration | `<modules>` declaration in parent POM, service/lib modules |
| AC3 | Directory structure | All PRD directories created with README placeholders |
| AC4 | Build commands | `mvn clean verify`, `mvn test`, `mvn package -pl <module>` |
| AC5 | TypeScript config → Java config | Java 21, Spring Boot 3.x BOM, compiler plugin config |

### Key Decisions
1. **Maven over Gradle** — PRD section 7.6.1 specifies Maven. Simpler XML-based config, wider Spring Boot documentation coverage.
2. **Spring Boot BOM** — Use `spring-boot-starter-parent` as parent for dependency management.
3. **Maven wrapper** — Include `mvnw`/`mvnw.cmd` so developers don't need Maven pre-installed.
4. **Java 21 virtual threads** — Configured but not activated until service stories.
5. **Multi-module layout** — services/, libs/ as Maven modules; web/ kept separate (React/Vite, not Maven).
6. **Verification test** — A build-level integration test in a dedicated module that validates the project structure.
