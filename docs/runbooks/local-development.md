# Local Development Runbook

> How to set up and run the Orion platform on your local machine.

---

## Prerequisites

| Tool | Minimum Version | Verify Command |
|------|----------------|----------------|
| **Java (JDK)** | 21 | `java -version` |
| **Maven** | 3.9+ (or use the included wrapper) | `mvn -version` |
| **Git** | 2.40+ | `git --version` |

> **Tip:** You don't need to install Maven globally — the project includes a Maven Wrapper (`mvnw` / `mvnw.cmd`) that downloads the correct version automatically.

---

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/<org>/Orion.git
cd Orion
```

### 2. Verify Java Version

```bash
java -version
# Expected: openjdk version "21.x.x"
```

If you don't have Java 21, install [Eclipse Temurin 21](https://adoptium.net/).

### 3. Build the Project

**Using the Maven Wrapper (recommended):**

```bash
# Linux / macOS
./mvnw clean verify

# Windows (PowerShell)
.\mvnw.cmd clean verify
```

**Using a globally installed Maven:**

```bash
mvn clean verify
```

### 4. Run Tests Only

```bash
# All unit tests
./mvnw test

# A specific module
./mvnw test -pl build-tools/verification

# Integration tests (requires Docker for Testcontainers)
./mvnw verify -P it
```

### 5. Run with Code Coverage

```bash
./mvnw verify -P coverage
# Report: target/site/jacoco/index.html (per module)
```

---

## Project Structure

```
Orion/
├── pom.xml                  # Root parent POM
├── mvnw / mvnw.cmd          # Maven wrapper
├── services/                # Microservice modules
├── libs/                    # Shared library modules
├── proto/                   # Protobuf definitions
├── schemas/                 # Event schemas
├── web/                     # React frontend
├── infra/                   # Docker Compose & Terraform
├── docs/                    # Documentation
├── benchmarks/              # Performance benchmarks
├── scripts/                 # Utility scripts
└── build-tools/
    └── verification/        # Structural verification tests
```

---

## Common Commands

| Task | Command |
|------|---------|
| Full build + tests | `./mvnw clean verify` |
| Unit tests only | `./mvnw test` |
| Single module | `./mvnw test -pl services/<name>` |
| Integration tests | `./mvnw verify -P it` |
| Code coverage | `./mvnw verify -P coverage` |
| Skip tests | `./mvnw install -DskipTests` |
| Dependency tree | `./mvnw dependency:tree` |
| Check for updates | `./mvnw versions:display-dependency-updates` |

---

## IDE Setup

### IntelliJ IDEA
1. **File → Open** → select the root `pom.xml` → **Open as Project**
2. Ensure **Project SDK** is set to Java 21
3. Enable **Maven auto-import**

### VS Code
1. Install the **Extension Pack for Java** (vscjava.vscode-java-pack)
2. Open the `Orion/` folder
3. VS Code auto-detects the Maven project

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `JAVA_HOME` not set | Set it to your JDK 21 installation path |
| Maven wrapper fails to download | Check your internet connection; verify `.mvn/wrapper/maven-wrapper.properties` |
| Tests fail with "user.dir" errors | Run Maven from the **project root** (`Orion/`), not a subdirectory |
| Port already in use | Check `lsof -i :<port>` (Linux/Mac) or `netstat -ano \| findstr :<port>` (Windows) |

---

*Last updated after US-01-01*
