# Local Development Runbook

> How to set up and run the Orion platform on your local machine.

---

## Prerequisites

| Tool | Minimum Version | Verify Command |
|------|----------------|----------------|
| **Java (JDK)** | 21 | `java -version` |
| **Maven** | 3.9+ (or use the included wrapper) | `.\mvnw.cmd -version` |
| **Docker** | 24+ | `docker --version` |
| **Docker Compose** | v2+ | `docker compose version` |
| **Git** | 2.40+ | `git --version` |

> **Tip:** You don't need to install Maven globally — the project includes a Maven Wrapper (`mvnw.cmd`) that downloads the correct version automatically.

---

## Getting Started

### 1. Clone the Repository

```powershell
git clone https://github.com/<org>/Orion.git
cd Orion
```

### 2. Verify Java Version

```powershell
java -version
# Expected: openjdk version "21.x.x" (Eclipse Temurin 21)
```

If you don't have Java 21, install [Eclipse Temurin 21](https://adoptium.net/).

### 3. Start Local Infrastructure

```powershell
cd infra/docker-compose
docker compose up -d
```

Wait ~15 seconds, then verify all containers are healthy:

```powershell
docker compose ps
```

**Available services after startup:**

| Service | URL / Port | Purpose |
|---------|-----------|---------|
| **Redpanda** (Kafka) | localhost:19092 | Event bus |
| **Redpanda Console** | http://localhost:8080 | Kafka UI — topics, messages, consumers |
| **PostgreSQL** | localhost:5432 | Primary database |
| **pgAdmin** | http://localhost:5050 | Database UI |
| **Redis** | localhost:6379 | Cache / read-side store |
| **Redis Commander** | http://localhost:8081 | Redis UI |

### 4. Build the Project

```powershell
# Full build + all tests (390 tests across 5 modules)
.\mvnw.cmd clean verify
```

**Expected output:**
```
[INFO] Orion Platform ........ SUCCESS
[INFO] Orion Event Model ..... SUCCESS  (47 tests)
[INFO] Orion Security ........ SUCCESS  (75 tests)
[INFO] Orion Observability ... SUCCESS  (102 tests)
[INFO] Orion gRPC API ........ SUCCESS  (81 tests)
[INFO] Build Verification .... SUCCESS  (85 tests)
```

### 5. Run Tests Only

```powershell
# All unit tests
.\mvnw.cmd test

# A specific module
.\mvnw.cmd test -pl libs/security

# A specific test class
.\mvnw.cmd test -pl libs/grpc-api -Dtest=RfqProtoTest

# A specific test method
.\mvnw.cmd test -pl libs/grpc-api -Dtest="RfqProtoTest#shouldBuildCreateRequest"

# Integration tests (requires Docker for Testcontainers)
.\mvnw.cmd verify -P it
```

### 6. Proto Compilation

Proto files are compiled automatically during `mvn compile`. To compile only:

```powershell
.\mvnw.cmd compile -pl libs/grpc-api
```

This generates **132 Java files** from the proto definitions in `target/generated-sources/protobuf/`. If your IDE shows red underlines on proto classes, run this command and reload.

### 7. Run with Code Coverage

```powershell
.\mvnw.cmd verify -P coverage
# Report: target/site/jacoco/index.html (per module)
```

---

## Project Structure

```
Orion/
├── pom.xml                  # Root parent POM (Java 21, Spring Boot 3.4.3)
├── mvnw.cmd / mvnw          # Maven wrapper
├── libs/                    # Shared library modules
│   ├── event-model/         #   EventEnvelope, EventType, 30 event types (47 tests)
│   ├── security/            #   JWT, RBAC, tenant isolation (75 tests)
│   ├── observability/       #   Metrics, tracing, MDC propagation (102 tests)
│   └── grpc-api/            #   Protobuf + gRPC contracts (81 tests)
├── services/                # Domain service modules (planned)
├── infra/
│   └── docker-compose/      # Local dev infrastructure
├── docs/                    # Full documentation suite
│   ├── architecture/        #   System architecture, services, communication, diagrams
│   ├── api/                 #   gRPC, REST, event catalog
│   ├── runbooks/            #   This file + debugging + deployment
│   ├── adr/                 #   5 Architecture Decision Records
│   ├── eli5-guide.md        #   Beginner-friendly guide
│   └── knowledge.md         #   Living knowledge base
├── build-tools/
│   └── verification/        # Structural verification tests (85 tests)
├── scripts/                 # Utility scripts
└── user-stories/            # Story specifications and status
```

---

## Common Commands

| Task | Command |
|------|---------|
| **Full build + tests** | `.\mvnw.cmd clean verify` |
| **Quick compile** | `.\mvnw.cmd compile` |
| **Unit tests only** | `.\mvnw.cmd test` |
| **Single module** | `.\mvnw.cmd verify -pl libs/grpc-api -am` |
| **Single test class** | `.\mvnw.cmd test -pl libs/security -Dtest=RbacTest` |
| **Proto compile only** | `.\mvnw.cmd compile -pl libs/grpc-api` |
| **Integration tests** | `.\mvnw.cmd verify -P it` |
| **Code coverage** | `.\mvnw.cmd verify -P coverage` |
| **Skip tests** | `.\mvnw.cmd install -DskipTests` |
| **Dependency tree** | `.\mvnw.cmd dependency:tree -pl libs/grpc-api` |
| **Check for updates** | `.\mvnw.cmd versions:display-dependency-updates` |

---

## Docker Compose Commands

| Task | Command |
|------|---------|
| **Start all** | `cd infra/docker-compose ; docker compose up -d` |
| **Stop (keep data)** | `cd infra/docker-compose ; docker compose down` |
| **Stop (wipe data)** | `cd infra/docker-compose ; docker compose down -v` |
| **View logs** | `cd infra/docker-compose ; docker compose logs -f <service>` |
| **Check health** | `cd infra/docker-compose ; docker compose ps` |
| **Kafka topics** | `docker compose exec redpanda rpk topic list` |
| **psql connect** | `docker compose exec postgres psql -U orion -d orion` |

---

## IDE Setup

### VS Code (Recommended)
1. Install the **Extension Pack for Java** (`vscjava.vscode-java-pack`)
2. Open the `Orion/` root folder
3. VS Code auto-detects the Maven project
4. If proto classes show red underlines: `.\mvnw.cmd compile -pl libs/grpc-api` then reload

### IntelliJ IDEA
1. **File → Open** → select the root `pom.xml` → **Open as Project**
2. Ensure **Project SDK** is set to Java 21
3. Enable **Maven auto-import**
4. Mark `target/generated-sources/protobuf/` as Generated Sources Root

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `JAVA_HOME` not set | Set it to your JDK 21 installation path |
| Maven wrapper fails | Check internet; verify `.mvn/wrapper/maven-wrapper.properties` |
| Tests fail "user.dir" error | Run Maven from the **project root** (`Orion/`), not a subdirectory |
| Port already in use | `netstat -ano \| findstr :<port>` → `Stop-Process -Id <PID>` |
| Proto classes not found | `.\mvnw.cmd clean compile -pl libs/grpc-api` then reload IDE |
| Docker Compose won't start | Verify Docker Desktop is running; check `docker compose logs` |
| Redpanda won't connect | Confirm port 19092 is free; check `docker compose ps` |

---

*Last updated after US-01-06 — 390 tests passing across 5 modules*
