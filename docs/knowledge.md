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
