# ADR-001: Maven Multi-Module Monorepo Structure

## Status
Accepted

## Date
2026-02-12

## Context
Orion is an institutional trading platform with ~10 backend microservices, 4+ shared libraries, 2 frontend apps, and infrastructure code. We need a repository structure that:
- Allows all components to live in a single repository (monorepo)
- Supports Java 21 + Spring Boot 3.x backend services
- Supports React + TypeScript frontend applications
- Enables shared dependency management across all Java modules
- Supports building individual modules or the entire project

## Decision
We will use a **Maven multi-module** project with a parent POM at the repository root.

### Structure
```
pom.xml (parent)
├── libs/         (shared Java libraries — Maven modules)
│   ├── event-model/
│   ├── security/
│   ├── observability/
│   └── common/
├── services/     (Spring Boot microservices — Maven modules)
│   ├── bff-workstation/
│   ├── rfq-service/
│   └── ...
├── web/          (React frontends — NOT Maven modules)
│   ├── workstation/
│   └── admin-console/
└── infra/        (Terraform, Docker Compose — NOT Maven modules)
```

### Key Design Choices
1. **Parent POM** uses `spring-boot-starter-parent` as its parent for Spring Boot BOM dependency management.
2. **Shared libraries** (`libs/`) are plain JAR modules (not Spring Boot applications).
3. **Services** (`services/`) are Spring Boot applications with executable JARs.
4. **Frontend** (`web/`) is excluded from Maven — it uses Vite/npm for build.
5. **Maven wrapper** (`mvnw`) is included for reproducible builds.

## Consequences

### Positive
- Single `mvn clean verify` builds and tests all Java modules
- Dependency versions are managed centrally in parent POM
- Inter-module dependencies use standard Maven `<dependency>` declarations
- Maven reactor automatically determines build order
- Wide IDE support (IntelliJ IDEA, VS Code with Java extensions)

### Negative
- Maven XML is verbose compared to Gradle Kotlin DSL
- No built-in "affected" builds (unlike Nx) — Maven always considers all modules
- Frontend must use a separate build system (npm/Vite)

### Mitigations
- Use `mvn package -pl services/rfq-service -am` for targeted builds with dependencies
- CI can use `mvn -pl` with change detection scripts for affected-only builds
- Frontend build can be integrated via `frontend-maven-plugin` if desired later

## Alternatives Considered

| Alternative | Reason Rejected |
|---|---|
| Gradle multi-project | PRD specifies Maven; team familiarity |
| Nx with Java plugin | Experimental, limited Java support |
| Separate repositories | Loses monorepo benefits (shared libs, atomic commits) |
| Bazel | Over-engineered for this project size |
