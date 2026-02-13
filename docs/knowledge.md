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

---

## US-01-07: Configure GitHub Actions CI Pipeline

### Business Context

Before any production code lands on `main`, the team needs automated quality gates. US-01-07 creates the GitHub Actions CI pipeline that enforces build integrity, runs all tests, validates proto definitions, scans for dependency vulnerabilities, and produces Docker images — all triggered automatically on PRs and pushes.

### Reinterpretation of US-01-07

The original story was written for Nx/TypeScript/Node.js tooling (npm ci, nx affected, buf lint, Dockerfile.node). We reinterpret every acceptance criterion for Java 21 / Maven / Spring Boot:

| Original (Nx/TypeScript) | Reinterpreted (Java 21/Maven) |
|---|---|
| `npm ci` / `npx nx affected:build` | `./mvnw verify -B --no-transfer-progress` (Maven reactor builds all) |
| Nx computation caching | Maven dependency caching via `actions/setup-java@v4 cache: maven` in reusable composite action |
| `buf lint` / `buf breaking` | `./mvnw compile -pl libs/grpc-api -am` (protobuf-maven-plugin compiles protos) |
| Jest test reports | JUnit XML via `dorny/test-reporter@v1` + Surefire reports |
| Codecov coverage upload | JaCoCo HTML reports via `./mvnw verify -Pcoverage` |
| `npm audit` / Snyk | `aquasecurity/trivy-action@v0` filesystem scan + SARIF upload to GitHub Security |
| Dockerfile per service (Node.js) | `services/Dockerfile.template` — multi-stage eclipse-temurin:21-jdk → 21-jre-alpine |
| Docker Hub push | ghcr.io (GitHub Container Registry) via `docker/login-action@v3` |
| GitHub Actions pinned to `@v3` | All actions pinned to exact major version (`@v4`, `@v3`, `@v1`, etc.) |

### Acceptance Criteria Mapping

| AC | Description | Deliverables |
|---|---|---|
| AC1 | Pull Request workflow | `.github/workflows/ci-pr.yml` — triggers on PRs to main, Java 21, `mvnw verify`, JUnit test reporter, concurrency cancel-in-progress |
| AC2 | Main branch workflow | `.github/workflows/ci-main.yml` — triggers on push to main, `mvnw verify -Pcoverage`, JaCoCo reports, Surefire XML upload, dependency audit job |
| AC3 | Build caching | `.github/actions/setup-java-maven/action.yml` — reusable composite action with Maven cache; all workflows reference it |
| AC4 | Test reporting | `dorny/test-reporter@v1` reads `**/target/surefire-reports/*.xml`, creates GitHub check annotations |
| AC5 | Proto validation | `.github/workflows/proto-validate.yml` — triggers on proto file changes, compiles and tests grpc-api module |
| AC6 | Security scanning | Trivy filesystem scan in `ci-main.yml` dependency-audit job → SARIF → GitHub Security tab |
| AC7 | Docker build | `.github/workflows/docker-build.yml` — multi-stage build, ghcr.io push, SHA+branch+latest tags |

### CI Architecture

```
.github/
├── actions/
│   └── setup-java-maven/
│       └── action.yml                 ← Reusable composite action (JDK 21 + Maven cache)
├── workflows/
│   ├── ci-pr.yml                      ← PR validation: build + test + report
│   ├── ci-main.yml                    ← Main CI: build + coverage + dependency audit
│   ├── proto-validate.yml             ← Proto compilation check on proto changes
│   └── docker-build.yml               ← Docker image build + push to ghcr.io
├── CODEOWNERS                         ← Automatic PR review routing
└── ...
services/
└── Dockerfile.template                ← Multi-stage Docker template for Spring Boot services
```

### Key Decisions

1. **Reusable composite action** — `.github/actions/setup-java-maven/action.yml` sets up JDK 21 (Temurin) and Maven cache in one step. All 4 workflows reference it, eliminating duplication. Changes to Java version or cache strategy are made in one place.
2. **Concurrency cancel-in-progress** — PR workflow uses `concurrency: group: pr-${{ github.event.pull_request.number }}` with `cancel-in-progress: true`. New pushes to the same PR cancel stale runs, saving runner minutes.
3. **Trivy over OWASP Dependency-Check** — Trivy is faster, produces SARIF natively, and integrates with GitHub Security tab. OWASP dependency-check is heavy and slow for CI. Trivy's `fs` mode scans Maven POM dependency tree.
4. **JaCoCo for coverage** — Activated via Maven profile (`-Pcoverage`). Only runs on main branch to avoid slowing PR builds. HTML reports uploaded as artifacts; no external coverage service required initially.
5. **GitHub Container Registry (ghcr.io)** — Free for public repos, integrated with GitHub. Uses `GITHUB_TOKEN` for authentication — no separate registry credentials needed.
6. **Multi-stage Dockerfile template** — `eclipse-temurin:21-jdk` for build → `eclipse-temurin:21-jre-alpine` for runtime. Non-root user (`orion:1001`), JVM container tuning (`-XX:+UseContainerSupport`, `-XX:MaxRAMPercentage=75.0`), ports 8080+9090.
7. **CODEOWNERS for review routing** — Global ownership plus path-specific rules for libs/, proto/, infra/, .github/, docs/. Ensures the right reviewers are auto-assigned.
8. **dorny/test-reporter for JUnit** — Reads Surefire XML reports and creates GitHub check annotations directly on PR diffs. Developers see test failures inline without digging through logs.
9. **Timeout on all workflows** — `timeout-minutes: 15` (PR) and `timeout-minutes: 20` (main) prevent hung builds from consuming runner hours.
10. **Docker build with paths-filter** — Uses `dorny/paths-filter@v3` to detect which services changed, building only affected Docker images. Placeholder structure ready for future services.

---

## US-01-02: Create Docker Compose Local Development Environment

### Business Context

Before any microservice can be developed, developers need a **local environment** that mirrors production infrastructure. This means running Kafka (message broker), PostgreSQL (database), and Redis (cache) locally — along with admin UIs for debugging. Without this, developers would need cloud access for every test, which is slow and expensive.

### Why Docker Compose?

Docker Compose lets us define all infrastructure services in a single YAML file. One command (`docker compose up`) spins up everything. This gives every developer an identical environment regardless of their OS.

### Service Architecture (Local Dev)

```
┌─────────────────────────────────────────────────────────┐
│                    Developer Machine                     │
│                                                         │
│  ┌─────────────┐  ┌──────────┐  ┌─────────────────┐   │
│  │  Redpanda    │  │ Postgres │  │     Redis        │   │
│  │ (Kafka API)  │  │   15     │  │       7          │   │
│  │  :19092      │  │  :5432   │  │     :6379        │   │
│  └──────┬───────┘  └────┬─────┘  └────────┬────────┘   │
│         │               │                  │            │
│  ┌──────┴───────┐  ┌────┴─────┐  ┌────────┴────────┐   │
│  │  Redpanda    │  │ pgAdmin  │  │ Redis Commander  │   │
│  │  Console     │  │  :5050   │  │     :8081        │   │
│  │   :8080      │  │          │  │                  │   │
│  └──────────────┘  └──────────┘  └─────────────────┘   │
│                                                         │
│              orion-network (Docker bridge)               │
└─────────────────────────────────────────────────────────┘
```

### Port Mapping (External)

| Service | External Port | Internal Port | Purpose |
|---|---|---|---|
| Redpanda (Kafka) | 19092 | 9092 | Kafka bootstrap for Spring Boot apps |
| Redpanda (Schema Registry) | 18081 | 8081 | Avro/Protobuf schema management |
| Redpanda (HTTP Proxy) | 18082 | 8082 | REST Proxy for Kafka |
| Redpanda Console | 8080 | 8080 | Kafka UI for debugging |
| PostgreSQL | 5432 | 5432 | Database for all services |
| pgAdmin | 5050 | 80 | Database admin UI |
| Redis | 6379 | 6379 | Cache/session store |
| Redis Commander | 8081 | 8081 | Redis admin UI |

### Spring Boot Connection Properties (for future services)

```yaml
# application-local.yml (to be used in service stories)
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orion
    username: orion
    password: orion_dev_password
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:19092
```

### Acceptance Criteria Mapping

| AC | Description | Deliverables |
|---|---|---|
| AC1 | Core infrastructure services | docker-compose.yml with Redpanda, PostgreSQL, Redis on orion-network |
| AC2 | Dev tooling containers | Redpanda Console (:8080), pgAdmin (:5050), Redis Commander (:8081) |
| AC3 | Configuration & secrets | .env.example, .env (gitignored), init-scripts/ |
| AC4 | Developer experience | docker compose up/down, health checks, depends_on |
| AC5 | Data persistence | Named volumes, reset script |
| AC6 | Documentation | Updated README, troubleshooting, port mapping, resource requirements |

### Key Decisions

1. **Redpanda over Apache Kafka** — Lighter resource footprint for local dev; API-compatible with Kafka. Production will use AWS MSK or Azure Event Hubs.
2. **PostgreSQL 15 Alpine** — Matches likely production version; Alpine image is smaller.
3. **Redis 7 with AOF persistence** — `appendonly yes` for durability; `allkeys-lru` eviction for bounded memory.
4. **Named Docker volumes** — Data survives `docker compose down` but can be wiped with `-v` flag.
5. **Externalized .env** — Credentials in `.env` (gitignored); `.env.example` committed as template.
6. **Init scripts** — PostgreSQL init scripts create per-service databases + app user on first run.
7. **Both bash and PowerShell scripts** — Team uses Windows and macOS/Linux.

---

## US-01-03: Setup Shared Event Model Library

### Business Context

Orion is an **event-driven** platform. Every action — a trade executing, a quote arriving, a risk limit being breached — produces an **event** that is published to Kafka and consumed by other services. For this to work, every service must speak the same language: a canonical **event envelope** that wraps every domain event with standard metadata (IDs, timestamps, correlation, tenant isolation).

This library (`orion-event-model`) is the first *real* Java code in the monorepo. It's a **pure domain library** with zero Spring dependencies — any Java project can use it.

### Reinterpretation of US-01-03

The original user story was written with TypeScript/Nx in mind. We reinterpret for Java 21 + Maven:

| Original (TypeScript) | Reinterpreted (Java 21) |
|---|---|
| `EventEnvelope<T>` interface | Java record `EventEnvelope<T>` (immutable, generic) |
| `EventEntity` interface | Java record `EventEntity` |
| `createEvent<T>()` factory function | `EventFactory.create()` static method |
| `createChildEvent<T>()` | `EventFactory.createChild()` static method |
| `createEventId()` / `createCorrelationId()` | `java.util.UUID.randomUUID()` |
| `serializeEvent()` / `deserializeEvent()` | `EventSerializer` with Jackson ObjectMapper |
| JSON Schema validation | `EventValidator.validate()` with manual field checks |
| `EventTypes` const object | `EventType` Java enum |
| `isKnownEventType()` type guard | `EventType.fromString()` returning `Optional` |
| `@orion/event-model` import | Maven `<dependency>com.orion:orion-event-model</dependency>` |
| `Instant` as ISO 8601 string | `java.time.Instant` (Jackson JavaTimeModule serializes to ISO 8601) |

### Package Structure

```
com.orion.eventmodel/
├── EventEnvelope.java        — Record: canonical event wrapper with generic payload
├── EventEntity.java          — Record: entity tracking (type, id, sequence)
├── EventType.java            — Enum: all known Orion event types
├── EntityType.java           — Enum: all known entity types
├── EventFactory.java         — Static factory methods for creating events
├── EventSerializer.java      — Jackson-based JSON serialization/deserialization
├── ValidationResult.java     — Record: validation outcome with error list
└── EventValidator.java       — Validates envelope fields
```

### Design Decisions

1. **Java records** — `EventEnvelope<T>` and `EventEntity` are records: immutable, compact, auto-generated equals/hashCode/toString. Perfect for event data that should never be mutated after creation.
2. **No Spring dependency** — This is a pure domain library. Only Jackson for JSON. Any Java project can use it, not just Spring Boot apps.
3. **`Instant` for timestamps** — Type-safe, nanosecond precision, ISO 8601 serialization via Jackson's `JavaTimeModule`.
4. **Enums for event/entity types** — Compile-time safety, exhaustive switch statements, easy to extend.
5. **Jackson for serialization** — Spring Boot's default JSON library. The `JavaTimeModule` handles `Instant` ↔ ISO 8601. Generic `T` payload is handled via `TypeReference` or `JavaType`.
6. **Manual validation over Bean Validation** — No annotation processing dependency. Simple, fast, returns a `ValidationResult` record with all errors at once.
7. **Builder pattern** — While records are immutable, we provide a fluent builder via `EventFactory` for ergonomic event creation with sensible defaults.

---

## US-01-04: Setup Shared Security Library

### Business Context

Orion is a **multi-tenant** trading platform. Every API call and gRPC request must carry authentication (who is the caller?), authorization (what are they allowed to do?), and tenant isolation (which firm's data can they see?). Without a shared security library, every service would re-implement these checks differently, creating security gaps and inconsistencies.

This library (`orion-security`) provides the **security vocabulary** — the records, enums, and pure-Java utilities that all services share. Actual JWT validation and Spring Security filter integration happen at the service layer (Spring Security Resource Server). This library stays framework-agnostic, just like `orion-event-model`.

### Reinterpretation of US-01-04

The original story was written for TypeScript/Express.js. We reinterpret for Java 21:

| Original (TypeScript/Express) | Reinterpreted (Java 21) |
|---|---|
| `SecurityContext` interface | `OrionSecurityContext` record |
| `AuthenticatedUser` interface | `AuthenticatedUser` record |
| `TenantContext` interface | `TenantContext` record |
| `Entitlements` interface | `Entitlements` record (with `Set<AssetClass>`) |
| `TradingLimits` interface | `TradingLimits` record |
| `Roles` const object | `Role` Java enum with hierarchy support |
| `AssetClass` enum | `AssetClass` Java enum |
| `extractBearerToken()` function | `BearerTokenExtractor.extract()` static method |
| `hasRole()` / `requireRole()` | `RoleChecker.hasRole()` / `hasAnyRole()` / `hasAllRoles()` |
| `checkEntitlement()` | `EntitlementChecker` with asset class/instrument/venue/limit checks |
| `enforceTenantIsolation()` | `TenantIsolationEnforcer.enforce()` throws `TenantMismatchException` |
| Express auth middleware | Deferred to service layer (Spring Security filter) |
| gRPC interceptors | Deferred to service layer (grpc-java ServerInterceptor) |
| `serializeContext()` / `deserializeContext()` | `SecurityContextSerializer` with JSON + Base64 |
| Mock JWT generator | `TestSecurityContextFactory` in `com.orion.security.testing` |

### Package Structure

```
com.orion.security/
├── OrionSecurityContext.java         — Record: full security context (user + tenant + roles + entitlements)
├── AuthenticatedUser.java            — Record: authenticated user info
├── TenantContext.java                — Record: tenant info for isolation
├── TradingLimits.java                — Record: per-user trading limits
├── Entitlements.java                 — Record: ABAC entitlements (asset classes, instruments, venues, limits)
├── Role.java                         — Enum: platform roles with hierarchy (ADMIN > SALES > TRADER)
├── AssetClass.java                   — Enum: tradeable asset classes
├── TenantType.java                   — Enum: tenant tiers (STANDARD, PREMIUM, ENTERPRISE)
├── BearerTokenExtractor.java         — Utility: extracts token from "Bearer xxx" header
├── RoleChecker.java                  — Utility: RBAC checks with hierarchy support
├── EntitlementChecker.java           — Utility: ABAC checks for asset class/instrument/venue/limits
├── TenantIsolationEnforcer.java      — Utility: enforces tenant match, throws on mismatch
├── TenantMismatchException.java      — Runtime exception for tenant isolation violations
├── SecurityContextSerializer.java    — Utility: JSON+Base64 serialize/deserialize for gRPC propagation
├── SecurityContextValidator.java     — Utility: validates required security context fields
├── SecurityValidationResult.java     — Record: validation outcome with error list
└── testing/
    └── TestSecurityContextFactory.java — Test utility: creates mock contexts for service tests
```

### Design Decisions

1. **Pure Java library — no Spring dependency** — Consistent with `orion-event-model`. Spring Security filter integration deferred to service stories. This keeps the library usable by any Java project.
2. **Records for all value types** — `OrionSecurityContext`, `AuthenticatedUser`, `TenantContext`, `Entitlements`, `TradingLimits` are all immutable records. Thread-safe by design for concurrent trading workloads.
3. **Role hierarchy via enum** — `ADMIN.implies(TRADER)` returns true. This avoids scattering hierarchy logic across services. The hierarchy is defined once in the enum.
4. **ABAC with entitlements** — `Entitlements` record holds allowed asset classes, instruments, venues, and trading limits. Empty set = "all allowed" (no restrictions).
5. **Explicit tenant isolation** — `TenantIsolationEnforcer.enforce()` throws `TenantMismatchException` (a RuntimeException). Fail-fast on cross-tenant access. Every service must call this before accessing tenant-scoped resources.
6. **SecurityContextSerializer for gRPC propagation** — Service-to-service calls carry security context in gRPC metadata as Base64-encoded JSON. The serializer handles the encode/decode.
7. **TestSecurityContextFactory in main source** — Placed in `com.orion.security.testing` package in `src/main` so other modules can import it in their test scope. Avoids the complexity of Maven test-jars.
8. **Jackson for serialization** — Same as event-model. Used only for SecurityContextSerializer (gRPC metadata propagation).

---

## US-01-05: Setup Shared Observability Library

### Business Context

Orion is a **distributed, event-driven** trading platform with multiple microservices communicating via gRPC and Kafka. When a trade request flows from the UI through the BFF, to the RFQ service, to the execution service, and finally to post-trade — debugging a failure anywhere in that chain requires **correlation**: the ability to trace a single request across all services using a shared correlation ID, structured logs, distributed traces, and metrics.

This library (`orion-observability`) provides the shared **observability primitives** that every service will use. It's the telescope, the dashboard gauges, and the logbook that let operators see inside the running system. Without it, each service would invent its own logging format, metric naming, and correlation strategy — making production debugging nearly impossible.

### Reinterpretation of US-01-05

The original story was written for TypeScript/Node.js (pino, prom-client, @opentelemetry/sdk-node, Express middleware). We reinterpret for Java 21:

| Original (TypeScript) | Reinterpreted (Java 21) |
|---|---|
| `pino` logger with `AsyncLocalStorage` | SLF4J API + MDC (Mapped Diagnostic Context) for correlation propagation |
| `createLogger(serviceName)` factory | `ObservabilityContext` record + `CorrelationContext` record + MDC integration |
| Sensitive field redaction | `SensitiveDataRedactor` utility with configurable field patterns |
| `@opentelemetry/sdk-node` | OpenTelemetry Java API (`opentelemetry-api`) — SDK wiring deferred to services |
| `createSpan()` / `withSpan()` | `SpanHelper` wrapping OTel `Tracer` with correlation ID propagation |
| `prom-client` counters/gauges/histograms | Micrometer `MeterRegistry` with `MetricFactory` wrapper for tenant labels |
| Express middleware (AC4) | **Deferred** — Spring MVC filters are service-layer concerns |
| gRPC interceptors (AC5) | **Deferred** — grpc-java `ServerInterceptor` in service modules |
| Kafka instrumentation (AC6) | **Deferred** — Spring Kafka + Micrometer integration in service modules |
| Health check aggregation (AC7) | `HealthCheck` functional interface + `HealthCheckRegistry` (pure Java) |
| `/health`, `/health/ready`, `/health/live` endpoints | **Deferred** — Spring Boot Actuator in service modules; library provides abstractions |
| Mock logger for tests | `TestCorrelationContext` factory + `InMemoryHealthCheck` in `testing` package |

### Package Structure

```
com.orion.observability/
├── CorrelationContext.java           — Record: correlationId, tenantId, userId, requestId, spanId, traceId
├── ObservabilityContext.java         — Record: aggregates correlation + service metadata for MDC injection
├── CorrelationContextHolder.java     — ThreadLocal + MDC bridge for context propagation
├── SensitiveDataRedactor.java        — Utility: redacts passwords, tokens, secrets from log data maps
├── SpanHelper.java                   — Utility: wraps OTel Tracer API for span creation with correlation
├── MetricFactory.java                — Utility: wraps Micrometer MeterRegistry with auto tenant labels
├── HealthCheck.java                  — Functional interface: () → CompletableFuture<ComponentHealth>
├── HealthCheckRegistry.java          — Aggregates multiple health checks, returns HealthStatus
├── HealthStatus.java                 — Enum: HEALTHY, DEGRADED, UNHEALTHY
├── ComponentHealth.java              — Record: component name, status, message, latency
├── HealthResult.java                 — Record: overall status + map of component results + timestamp
└── testing/
    ├── TestCorrelationContextFactory.java  — Creates mock correlation contexts for service tests
    └── InMemoryHealthCheck.java            — Controllable health check for testing health aggregation
```

### Design Decisions

1. **Pure Java library — no Spring dependency** — Consistent with `orion-event-model` and `orion-security`. Micrometer and OpenTelemetry API are framework-agnostic Java libraries. Spring Boot Actuator, web filters, and gRPC interceptors are deferred to service-layer stories.
2. **SLF4J MDC for correlation propagation** — MDC (Mapped Diagnostic Context) is the standard Java mechanism for per-thread log context. Every log statement automatically includes `correlationId`, `tenantId`, `userId` without explicit passing. Works with Logback, Log4j2, or any SLF4J backend.
3. **Micrometer over raw Prometheus client** — Micrometer is Spring Boot's native metrics facade. It supports Prometheus, CloudWatch, Datadog, and others. The `MetricFactory` wrapper auto-includes `tenant` label on every metric for multi-tenant segmentation.
4. **OpenTelemetry API only (not SDK)** — The library depends on `opentelemetry-api` (interface), not the SDK. Services configure the SDK (OTLP exporter, sampling, etc.) at boot time. This keeps the library thin and avoids version conflicts.
5. **Health check as functional interface** — `HealthCheck` is `@FunctionalInterface` returning `CompletableFuture<ComponentHealth>`. Services register checks (Postgres, Redis, Kafka) and the registry aggregates them. Async by default for non-blocking health probes.
6. **AC4 (Express/HTTP), AC5 (gRPC), AC6 (Kafka) deferred** — These are transport-layer integrations requiring Spring/gRPC/Kafka dependencies. The library provides the *what* (correlation context, metric factory, span helper); services provide the *how* (filters, interceptors, instrumentation).
7. **Test utilities in `src/main`** — Same pattern as `orion-security`. `TestCorrelationContextFactory` and `InMemoryHealthCheck` are in `src/main/java` so other modules can import them as a regular dependency in test scope.

---

## US-01-06: Setup Protobuf Definitions and Code Generation

### Business Context

Orion services communicate via **gRPC** — a high-performance RPC framework that uses Protocol Buffers (Protobuf) as its interface definition language (IDL). Before any service can expose or consume a gRPC API, we need a **shared contract library** that defines every message type, enum, and service interface. This library (`orion-grpc-api`) is the single source of truth for all service-to-service communication contracts.

Think of it as writing the **dictionary and grammar book** before anyone starts speaking the language. Every service — Market Data, RFQ, Execution, Post-Trade, Admin — must agree on the exact shape of requests and responses *before* any implementation begins.

### Reinterpretation of US-01-06

The original story was written for TypeScript/Node.js with Buf CLI. We reinterpret for Java 21 + Maven:

| Original (TypeScript/Buf) | Reinterpreted (Java 21/Maven) |
|---|---|
| `buf.yaml` configuration | `protobuf-maven-plugin` in module POM |
| `buf.gen.yaml` code generation config | `protoc` + `protoc-gen-grpc-java` plugins via Maven |
| Generated TypeScript stubs in `/libs/proto-gen/` | Generated Java classes in `target/generated-sources/protobuf/` (auto-compiled into JAR) |
| `npm run proto:generate` | `mvn compile` (automatic via plugin execution) |
| `buf lint` validation | Proto best practices enforced by conventions + compilation + tests |
| `buf breaking` compatibility check | Manual review + versioning strategy (v1 → v2 for breaking changes) |
| `@bufbuild/protobuf-ts` TypeScript stubs | `protobuf-java` message classes + `grpc-java` service stubs |
| Import as `@orion/proto-gen` | Maven dependency `com.orion:orion-grpc-api` |
| `/proto/v1/` directory at repo root | `libs/grpc-api/src/main/proto/v1/` (standard Maven layout) |

### Package Structure

```
libs/grpc-api/
├── pom.xml                                    — Module POM with protobuf-maven-plugin
└── src/
    ├── main/proto/
    │   └── v1/
    │       ├── common/
    │       │   ├── types.proto                — Timestamp, Money, Decimal, TenantContext, Side, AssetClass enums
    │       │   ├── pagination.proto           — PaginationRequest/Response for list operations
    │       │   └── errors.proto               — ErrorDetail, ErrorResponse
    │       ├── marketdata/
    │       │   └── marketdata.proto           — MarketDataService: GetSnapshot, StreamTicks, GetHistoricalTicks
    │       ├── rfq/
    │       │   └── rfq.proto                  — RFQService: CreateRFQ, GetRFQ, AcceptQuote, CancelRFQ, WatchRFQ
    │       ├── execution/
    │       │   └── execution.proto            — ExecutionService: GetTrade, ListTrades
    │       ├── posttrade/
    │       │   └── posttrade.proto            — PostTradeService: GetConfirmation, GetSettlementStatus
    │       └── admin/
    │           └── admin.proto                — AdminService: CreateInstrument, SetKillSwitch, UpdateLimits
    └── test/java/com/orion/grpc/
        ├── CommonTypesProtoTest.java          — Verifies common type construction and serialization
        ├── PaginationProtoTest.java           — Pagination message tests
        ├── ErrorsProtoTest.java               — Error message tests
        ├── MarketDataProtoTest.java           — Market data messages + service descriptor
        ├── RfqProtoTest.java                  — RFQ messages + service descriptor
        ├── ExecutionProtoTest.java            — Trade messages + service descriptor
        ├── PostTradeProtoTest.java            — Confirmation/settlement messages + service descriptor
        └── AdminProtoTest.java                — Instrument/limits messages + service descriptor
```

### Generated Java Packages (by protoc)

| Proto Package | Java Package | Contents |
|---|---|---|
| `orion.common.v1` | `com.orion.common.v1` | Timestamp, Money, Decimal, TenantContext, UserContext, CorrelationContext, Side, AssetClass |
| `orion.common.v1` | `com.orion.common.v1` | PaginationRequest, PaginationResponse, ErrorDetail, ErrorResponse |
| `orion.marketdata.v1` | `com.orion.marketdata.v1` | MarketDataServiceGrpc, SnapshotRequest, MarketTick, MarketSnapshot, etc. |
| `orion.rfq.v1` | `com.orion.rfq.v1` | RFQServiceGrpc, CreateRFQRequest, RFQDetails, Quote, RFQUpdate, etc. |
| `orion.execution.v1` | `com.orion.execution.v1` | ExecutionServiceGrpc, TradeDetails, TradeStatus, etc. |
| `orion.posttrade.v1` | `com.orion.posttrade.v1` | PostTradeServiceGrpc, ConfirmationDetails, SettlementDetails, etc. |
| `orion.admin.v1` | `com.orion.admin.v1` | AdminServiceGrpc, InstrumentDetails, KillSwitch messages, etc. |

### Design Decisions

1. **Maven module `libs/grpc-api`** — Keeps proto files + generated code in a standard Maven module. Other modules depend on `orion-grpc-api` as a regular Maven dependency. The `/proto/` root directory is kept as documentation reference.
2. **`protobuf-maven-plugin` + `protoc-gen-grpc-java`** — Standard Java ecosystem tools. `protoc` compiles .proto → Java message classes; `protoc-gen-grpc-java` generates gRPC service stubs (ImplBase, Stub, BlockingStub). No Buf CLI needed.
3. **`java_multiple_files = true`** — Each proto message becomes its own .java file instead of inner classes. IDE-friendly, easier imports, smaller compilation units.
4. **String-based financial amounts** — `Money.amount` and `Decimal.value` are strings, not doubles. Protobuf doesn't have a native decimal type, and floating-point is unsuitable for financial calculations. Services parse to `java.math.BigDecimal`.
5. **Custom `Timestamp` over `google.protobuf.Timestamp`** — The Orion timestamp is structurally identical but lives in our namespace for consistency. Could be replaced with the well-known type later if needed.
6. **Proto v1 versioning** — All definitions under `v1/` package. Breaking changes require a `v2/` directory with new package names. Within v1, only additive changes (new fields, new RPCs).
7. **`optional` keyword for nullable fields** — Proto3 `optional` generates `hasXxx()` methods in Java. Used for fields that are genuinely optional (e.g., `rfq_id` on a trade, `tenant_name` on context).
8. **Compiler warnings suppressed** — Generated protobuf code produces `-Xlint` warnings (unchecked casts, raw types). Module POM overrides compiler plugin with `-Xlint:none`.

---

## US-01-08: Setup Code Quality Tools and Standards

### Business Context

Code quality tooling is the invisible safety net that prevents style drift, enforces standards, and catches problems before they reach the repository. In a multi-developer platform like Orion, without automated formatting and linting, the codebase quickly devolves into inconsistent styles that cause merge conflicts and reduce readability.

### Reinterpretation of US-01-08

The original story was written for TypeScript/Nx tooling (ESLint, Prettier, Husky, commitlint). We reinterpret every acceptance criterion for Java 21 / Maven:

| Original (TypeScript/Nx) | Reinterpreted (Java 21/Maven) |
|---|---|
| ESLint with custom rules | **Checkstyle** — Google Java Style with Orion customizations (120-char lines, naming, imports) |
| Prettier for auto-formatting | **Spotless Maven Plugin** — Google Java Format (AOSP style), auto-format on `spotless:apply` |
| Husky + lint-staged (pre-commit hooks) | **`.githooks/`** directory with shell scripts, activated via `git config core.hooksPath .githooks` |
| commitlint (conventional commits) | **Shell regex validation** in `.githooks/commit-msg` — validates `type(scope): subject` pattern |
| VS Code settings for TypeScript | **VS Code settings for Java** — `.vscode/settings.json` + `.vscode/extensions.json` |
| EditorConfig | **Already present** from US-01-01, enhanced with proto file section |
| *(no equivalent)* | **Maven Enforcer Plugin** — enforces Java 21 minimum, Maven 3.9 minimum, bans duplicate deps |

### Tool Architecture

```
Build Lifecycle Quality Gates:
┌─────────────────────────────────────────────────────────┐
│  validate phase                                          │
│    ├── maven-enforcer-plugin (Java 21, Maven 3.9)       │
│    └── maven-checkstyle-plugin (build-tools/checkstyle.xml) │
│                                                          │
│  spotless:check (manual or -Pquality profile)            │
│    └── Google Java Format (AOSP style)                   │
│                                                          │
│  Git hooks (.githooks/)                                  │
│    ├── pre-commit → spotless:check                       │
│    └── commit-msg → conventional commit regex validation │
│                                                          │
│  IDE integration                                         │
│    ├── .vscode/settings.json → format on save            │
│    ├── .vscode/extensions.json → team extension recs     │
│    └── .editorconfig → charset, indent, line endings     │
└─────────────────────────────────────────────────────────┘
```

### Acceptance Criteria Mapping

| AC | Description | Deliverables |
|---|---|---|
| AC1 | Checkstyle config | `build-tools/checkstyle.xml` + `checkstyle-suppressions.xml`, `maven-checkstyle-plugin` in POM |
| AC2 | Spotless formatting | `spotless-maven-plugin` with `googleJavaFormat` (AOSP), `quality` profile for CI |
| AC3 | Git hooks | `.githooks/pre-commit` (Spotless check), `.githooks/commit-msg` (conventional commits), `.githooks/README.md` |
| AC4 | Conventional commits | Regex: `^(feat\|fix\|docs\|style\|refactor\|perf\|test\|chore\|revert\|ci\|build)(\([a-z][a-z0-9-]*\))?: .{1,100}$` |
| AC5 | VS Code settings | `.vscode/settings.json` (Java formatting), `.vscode/extensions.json` (team recommendations) |
| AC6 | EditorConfig | Enhanced with `[*.proto]` section (2-space indent per Google style) |
| AC+ | Maven Enforcer | `maven-enforcer-plugin` — `requireJavaVersion`, `requireMavenVersion`, `banDuplicatePomDependencyVersions` |

### Key Decisions

1. **Checkstyle over SpotBugs for initial setup** — Checkstyle catches style issues (naming, formatting, imports); SpotBugs catches bugs. Starting with style enforcement; bug detection can be added later.
2. **Google Java Style as base** — Industry standard, well-documented, IDE-supported. Orion customizes: 120-char lines (wider for financial domain), static wildcard imports allowed (test assertions).
3. **AOSP style (4-space indent) over Google style (2-space)** — More readable for complex financial domain code. Consistent with existing `.editorconfig` and Spring Boot conventions.
4. **Spotless in profile, not default build** — Running `spotless:check` on every `mvn verify` would break existing unformatted code immediately. The `quality` profile (`-Pquality`) is opt-in for CI; developers use `spotless:apply` to auto-fix.
5. **Shell-based commit-msg hook over commitlint** — No Node.js dependency required. Pure shell regex is simpler, faster, and works on any Unix-like system (Git Bash on Windows).
6. **`.githooks/` directory over `.git/hooks/`** — Version-controlled hooks. Developers activate with `git config core.hooksPath .githooks`. README documents the one-time setup.
7. **VS Code settings committed to Git** — `.gitignore` whitelists `!.vscode/settings.json` and `!.vscode/extensions.json`. Team-wide consistency without per-developer configuration drift.
8. **Maven Enforcer Plugin applied to ALL modules** — Runs in `validate` phase of every module build. Prevents Java 8 or Maven 3.6 from being used accidentally.
9. **Checkstyle suppressions for generated code** — Proto/gRPC generated code in `target/generated-sources/` is excluded from all checks. Test code gets relaxed rules (method names, magic numbers).
10. **`banDuplicatePomDependencyVersions` over `banDuplicateDependencies`** — Built-in enforcer rule in Maven 3.5+. Catches duplicate dependency declarations in POM which cause classloader conflicts.

---

## US-01-09: Create Base Service Template

### Business Context

Every Orion microservice (RFQ, execution, market-data, etc.) needs the same foundational infrastructure: HTTP endpoints, gRPC interceptors, health checks, correlation ID propagation, structured error handling, and externalized configuration. Without a reference template, each team would independently reinvent these patterns — inconsistently.

The **service template** is a fully working Spring Boot module that developers copy when starting a new service. It's the "golden path" — the Orion-approved way to build a service.

### Reinterpretation of US-01-09

The original story was written for Express/KafkaJS/Zod/TypeScript. We reinterpret every component for Java 21 / Spring Boot 3.x:

| Original (TypeScript/Express) | Reinterpreted (Java 21/Spring Boot) |
|---|---|
| Express HTTP server | **Spring Boot Web** (embedded Tomcat) |
| `main.ts` entry point | **`@SpringBootApplication`** main class |
| gRPC server initialization | **gRPC `ServerInterceptor`** classes (standalone, no starter yet) |
| KafkaJS consumer/producer | **Spring Kafka** (commented out, ready for US-05-xx) |
| Zod config validation | **`@ConfigurationProperties`** + **Bean Validation** (`@Validated`, `@NotBlank`) |
| Express middleware (correlation) | **`OncePerRequestFilter`** + gRPC `ServerInterceptor` |
| Express error middleware | **`@RestControllerAdvice`** with RFC 7807 `ProblemDetail` |
| Health check endpoint | **Spring Boot Actuator** (`/actuator/health`, `/actuator/prometheus`) |
| Graceful shutdown handler | **`server.shutdown=graceful`** (built into Spring Boot) |
| Database connection pool | **Spring Data JPA + HikariCP** (commented out, ready for US-01-10) |
| `.env` file config | **Spring profiles** (`application.yml`, `-local.yml`, `-docker.yml`) |

### Clean Architecture Package Layout

```
com.orion.servicetemplate/
├── api/                    # Inbound adapters (REST controllers)
│   └── ServiceInfoController     → /api/v1/info endpoint
├── config/                 # Cross-cutting configuration
│   ├── ServiceTemplateProperties → @ConfigurationProperties record
│   └── WebConfig                 → CORS configuration
├── domain/                 # Business logic (no framework imports!)
│   └── (entities, events, ports — added per service)
├── infrastructure/         # Outbound adapters (framework glue)
│   ├── web/
│   │   ├── CorrelationIdFilter     → HTTP correlation ID propagation
│   │   └── GlobalExceptionHandler  → RFC 7807 error mapping
│   └── grpc/
│       ├── GrpcCorrelationInterceptor → gRPC correlation ID
│       └── GrpcExceptionInterceptor   → Exception → Status mapping
└── ServiceTemplateApplication.java → @SpringBootApplication entry
```

### Correlation ID Flow

```
HTTP Request → CorrelationIdFilter → CorrelationContextHolder (ThreadLocal + MDC) → Response header
gRPC Request → GrpcCorrelationInterceptor → CorrelationContextHolder → Cleanup on complete/cancel
```

The correlation ID is either extracted from `X-Correlation-ID` header (HTTP) or `x-correlation-id` metadata key (gRPC), or generated as a UUID if absent. It flows through `CorrelationContextHolder` (from `orion-observability` lib) which populates SLF4J MDC for log correlation.

### gRPC Exception Mapping

| Java Exception | gRPC Status Code | Meaning |
|---|---|---|
| `IllegalArgumentException` | `INVALID_ARGUMENT` | Client sent bad data |
| `IllegalStateException` | `FAILED_PRECONDITION` | State prevents operation |
| `StatusRuntimeException` | *(preserved)* | Already a gRPC status |
| Any other exception | `INTERNAL` | Server-side bug |

### Spring Profile Strategy

| Profile | Activated By | Purpose |
|---|---|---|
| `default` | Always active | Base configuration, production-safe defaults |
| `local` | `-Dspring.profiles.active=local` | localhost DB/Kafka URLs, DEBUG logging |
| `docker` | `-Dspring.profiles.active=docker` | Container DNS names (postgres, redpanda) |
| `test` | `@ActiveProfiles("test")` | No external infra, WARN logging, random ports |

### Key Decisions

1. **Module in reactor build** — `services/service-template` is a real Maven module, built and tested on every `mvn verify`. This ensures the template stays compilable as libs evolve.
2. **`@ConfigurationProperties` record** — Java records with `@Validated` provide type-safe, immutable configuration with defaults in the compact constructor. No getters/setters needed.
3. **gRPC interceptors without Spring starter** — Only `io.grpc:grpc-api` (interfaces) is included. The full gRPC server runtime (`grpc-server-spring-boot-starter`) is added per-service when gRPC endpoints are needed. This keeps the template lightweight.
4. **RFC 7807 `ProblemDetail`** — Spring 6+ native error format. The `GlobalExceptionHandler` maps exceptions to structured JSON with `type`, `title`, `status`, `detail`, `timestamp`, and `correlationId`.
5. **Commented-out JPA/Kafka blocks** — The POM and YAML files contain commented sections for database and Kafka configuration. Developers uncomment them when adding data or event capabilities. This reduces "what do I need to add?" friction.
6. **`OncePerRequestFilter` for correlation** — Spring guarantees this filter runs exactly once per request (even with error dispatches). The `@Order(HIGHEST_PRECEDENCE)` ensures correlation is set before any other filter.
7. **`spring-boot-maven-plugin` for bootable JAR** — Each service produces an executable JAR that can be run with `java -jar`. The Dockerfile.template (from US-01-05) expects this.
8. **20 structural verification tests** — `BaseServiceTemplateTest` ensures the template's files, packages, and POM dependencies stay intact. If someone accidentally deletes a file, the verification module catches it.
9. **Clean Architecture enforcement by convention** — The package layout (`api → domain ← infrastructure`) is documented in `package-info.java` files. ArchUnit tests can enforce dependency rules in a future story.
10. **CORS for localhost only** — `WebConfig` allows `localhost:3000` and `localhost:5173` (React dev servers). Production CORS is configured via environment-specific profiles or API gateway.

## US-01-10: Setup Database Migration Framework

### Business Context

Every Orion microservice that persists data needs a reliable, version-controlled way to evolve database schemas. Without a migration framework, schema changes would be applied manually — leading to drift between environments, lost changes, and broken deployments.

The **database migration framework** provides:

- **Versioned SQL migrations** that are tracked and applied automatically
- **Multi-database support** for service-specific databases (orion, orion_rfq, orion_marketdata)
- **Seed data** for development and reference data for all environments
- **CI integration** via Maven — migrations are validated on every build

### Reinterpretation of US-01-10

The original story was written for node-pg-migrate / Prisma Migrate (TypeScript). We reinterpret every component for Java 21 / Spring Boot 3.x:

| Original (TypeScript/node-pg-migrate) | Reinterpreted (Java 21/Flyway) |
|---|---|
| `node-pg-migrate` CLI tool | **Flyway 10.x** (Spring Boot auto-configured) |
| `config.js` per-database URLs | **`@ConfigurationProperties` record** (`FlywayConfigProperties`) |
| `npm run db:migrate` | **`mvn flyway:migrate -pl libs/database`** |
| `npm run db:migrate:down` | **`mvn flyway:clean -pl libs/database`** (dev only) |
| `npm run db:migrate:create <name>` | **Manual `V{n}__{name}.sql`** file creation |
| `npm run db:migrate:status` | **`mvn flyway:info -pl libs/database`** |
| `npm run db:seed` | **`mvn flyway:migrate -Pflyway-seed`** (high-version migrations) |
| Sequential `001_` numbering | **Flyway `V{n}__` versioned naming** |
| Per-service migration directories | **Per-module classpath locations** (`db/migration/{db}/`) |
| `config.js` multi-database object | **`FlywayMultiDatabaseConfig`** Spring `@Configuration` |

### Architecture — `libs/database` Module

The migration framework lives in a shared library module (`libs/database`) that services depend on:

```
libs/database/
├── pom.xml                                      # Flyway deps + Maven plugin
└── src/
    ├── main/
    │   ├── java/com/orion/database/migration/
    │   │   ├── FlywayConfigProperties.java      # @ConfigurationProperties record
    │   │   ├── FlywayMultiDatabaseConfig.java   # Multi-DB @Configuration
    │   │   └── MigrationService.java            # Status/utility service
    │   └── resources/
    │       ├── application-flyway.yml            # Flyway config profile
    │       └── db/
    │           ├── migration/
    │           │   ├── orion/                    # V1__initial_schema.sql, V2__triggers.sql
    │           │   ├── rfq/                      # Placeholder (US-07-01)
    │           │   └── marketdata/               # Placeholder (US-06-01)
    │           └── seed/
    │               ├── development/              # V1000__seed_tenants, V1001__seed_users
    │               └── reference/                # R__reference_instruments, R__reference_venues
```

### Multi-Database Strategy

Each Orion microservice can have its own database. Flyway is configured per-database with separate beans:

| Database | Bean Name | Enabled By | Migration Location |
|---|---|---|---|
| `orion` (main) | `orionFlyway` | `orion.flyway.orion.enabled=true` | `classpath:db/migration/orion` |
| `orion_rfq` | `rfqFlyway` | `orion.flyway.rfq.enabled=true` | `classpath:db/migration/rfq` |
| `orion_marketdata` | `marketdataFlyway` | `orion.flyway.marketdata.enabled=true` | `classpath:db/migration/marketdata` |

Spring Boot's default `FlywayAutoConfiguration` is **disabled** — we manage Flyway beans ourselves via `@ConditionalOnProperty`.

### Initial Schema (V1 + V2)

The initial migration creates 7 foundational tables:

| Table | Purpose | Key Feature |
|---|---|---|
| `tenants` | Multi-tenant organizations | JSONB `settings` for per-tenant config |
| `users` | User accounts | Tenant-scoped uniqueness (email, username) |
| `user_roles` | RBAC role assignments | CASCADE delete with user |
| `user_entitlements` | Trading permissions | PostgreSQL arrays for flexible asset/venue lists |
| `outbox_events` | Transactional outbox | Partial index on unpublished events |
| `processed_events` | Idempotent consumers | Unique (tenant, consumer_group, event_id) |
| `audit_log` | Append-only audit trail | INET type for IP address |

V2 adds the `update_updated_at()` trigger function applied to tenants, users, and user_entitlements.

### Seed Data Strategy

| Type | Flyway Pattern | Applied When |
|---|---|---|
| Development seed | `V1000+__seed_*.sql` (high version) | `-Pflyway-seed` profile only |
| Reference data | `R__reference_*.sql` (repeatable) | Re-runs on checksum change |

Development seed data includes 4 tenants, 6 users, roles, and entitlements with realistic trading parameters.
Reference data includes 10 instruments (FX, Rates, Credit) and 8 trading venues.

### Key Decisions

1. **Flyway over Liquibase** — Flyway uses plain SQL files (matching the story's `.sql` migrations), has simpler config, and is the Spring Boot default.
2. **Shared `libs/database` module** — Centralized migrations on the classpath. Services depend on this JAR and Flyway auto-discovers migration files.
3. **`@ConfigurationProperties` record** — Type-safe, immutable config with Bean Validation. Replaces the TypeScript `config.js`.
4. **`@ConditionalOnProperty` per database** — Services only run migrations for their own database. RFQ service enables `rfq`, market data enables `marketdata`.
5. **H2 for unit tests** — Service-template tests use H2 in-memory instead of PostgreSQL. Integration tests (US-01-11) will use Testcontainers.
6. **JPA enabled in service-template** — `spring-boot-starter-data-jpa` + PostgreSQL driver now active (was commented out). `ddl-auto=validate` ensures Hibernate checks schema matches entities.
7. **Flyway Maven plugin** — Provides CLI-equivalent commands (`mvn flyway:migrate`, `flyway:info`, `flyway:clean`) for developer workflows.
8. **Maven profiles for multi-DB** — `-Pflyway-rfq`, `-Pflyway-marketdata`, `-Pflyway-seed` switch the plugin's target database.
9. **30 structural verification tests** — `DatabaseMigrationFrameworkTest` verifies directory structure, file naming, table definitions, and seed data presence.
10. **Docker Compose compatibility** — The `01-init-databases.sql` script (from US-01-02) already creates the per-service databases. Flyway runs inside the application, not as a Docker init script.
