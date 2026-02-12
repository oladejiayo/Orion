# Debugging Guide

> How to diagnose and resolve common issues in the Orion platform during development.

---

## 1. Build Failures

### Proto compilation fails

```
[ERROR] protoc failed: ...
```

**Cause:** `protoc` binary can't be downloaded or `.proto` syntax error.

**Fix:**
1. Check internet connectivity — `protoc` binary is downloaded on first compile
2. Look for syntax errors in `.proto` files: missing semicolons, bad imports
3. Run with verbose logging: `.\mvnw.cmd compile -pl libs/grpc-api -X`
4. Verify `os-maven-plugin` detected your OS: check for `os.detected.classifier` in logs

### Test compilation fails after proto change

If you modify a `.proto` file and tests won't compile:

```powershell
.\mvnw.cmd clean compile -pl libs/grpc-api
```

The `clean` is important — it removes stale generated classes.

### Maven wrapper fails to download

```
Error: Could not find or load main class org.apache.maven.wrapper.MavenWrapperMain
```

**Fix:**
```powershell
# Verify wrapper properties
Get-Content .mvn/wrapper/maven-wrapper.properties

# Re-download wrapper JAR
Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar" -OutFile ".mvn/wrapper/maven-wrapper.jar"
```

---

## 2. Test Failures

### Run a single test class

```powershell
.\mvnw.cmd test -pl libs/grpc-api -Dtest=RfqProtoTest
```

### Run a single test method

```powershell
.\mvnw.cmd test -pl libs/grpc-api -Dtest="RfqProtoTest#shouldBuildCreateRequest"
```

### Run tests for a specific module

```powershell
.\mvnw.cmd test -pl libs/security
```

### Run all tests with verbose output

```powershell
.\mvnw.cmd verify -X
```

### Test fails with "class not found" for generated proto class

Ensure you've compiled first:
```powershell
.\mvnw.cmd compile -pl libs/grpc-api
.\mvnw.cmd test -pl libs/grpc-api
```

---

## 3. Docker Compose Issues

### Container won't start

```powershell
cd infra/docker-compose
docker compose logs <service-name>
```

### Port already in use

```powershell
# Find what's using the port (Windows)
netstat -ano | findstr :5432

# Kill the process
Stop-Process -Id <PID> -Force
```

### Reset all data

```powershell
cd infra/docker-compose
docker compose down -v   # -v removes volumes (data)
docker compose up -d
```

### Redpanda/Kafka connection issues

```powershell
# Check if Redpanda is healthy
docker compose exec redpanda rpk cluster health

# List topics
docker compose exec redpanda rpk topic list

# Or use the Redpanda Console at http://localhost:8080
```

### PostgreSQL connection issues

```powershell
# Check if Postgres is accepting connections
docker compose exec postgres pg_isready -U orion

# Connect via psql
docker compose exec postgres psql -U orion -d orion

# Or use pgAdmin at http://localhost:5050
```

---

## 4. IDE Issues

### VS Code: Java extension not detecting Maven project

1. Ensure the **Extension Pack for Java** (`vscjava.vscode-java-pack`) is installed
2. Open the `Orion/` root folder (not a subfolder)
3. Wait for Java language server to initialize (check status bar)
4. If stuck: `Ctrl+Shift+P` → "Java: Clean Java Language Server Workspace"

### IntelliJ: Generated proto sources not resolved

1. Right-click `libs/grpc-api/target/generated-sources/protobuf/` → "Mark Directory as → Generated Sources Root"
2. Or run: File → Invalidate Caches → Restart

### Red underlines on generated proto classes

Generated classes are only available after compilation:
```powershell
.\mvnw.cmd compile -pl libs/grpc-api -am
```

Then reload the project in your IDE.

---

## 5. Correlation & Tracing

### Finding a request across services

Every request gets a `correlationId` that propagates across:
- REST headers (`X-Correlation-Id`)
- gRPC metadata (`x-correlation-id`)
- Kafka event envelope (`correlationId` field)
- Log output (via SLF4J MDC)

**To trace a request:**
1. Get the `correlationId` from the API response or log
2. Search all service logs for that ID:
   ```
   grep "corr-abc-123" logs/*.log
   ```

### SLF4J MDC fields (auto-populated by CorrelationContextHolder)

| MDC Key | Value |
|---------|-------|
| `correlationId` | End-to-end tracking ID |
| `tenantId` | Tenant scope |
| `userId` | Authenticated user |
| `requestId` | Per-request unique ID |
| `spanId` | OpenTelemetry span ID |
| `traceId` | OpenTelemetry trace ID |

---

## 6. Common Error Patterns

| Error | Likely Cause | Fix |
|-------|-------------|-----|
| `TenantMismatchException` | Cross-tenant data access attempt | Check `TenantIsolationEnforcer` call — ensure tenant context matches |
| `SecurityValidationResult.invalid` | Missing required security context fields | Check that JWT contains all required claims |
| `EventValidator` returns errors | Event envelope missing required fields | Use `EventFactory.create()` instead of manual construction |
| `UNAVAILABLE: io exception` (gRPC) | Target service not running | Check service health / Docker container |
| `DEADLINE_EXCEEDED` (gRPC) | Request timed out | Increase deadline or investigate service latency |

---

## 7. Useful Commands

```powershell
# Full build + all tests
.\mvnw.cmd clean verify

# Specific module only
.\mvnw.cmd clean verify -pl libs/security

# Skip tests
.\mvnw.cmd install -DskipTests

# Dependency tree
.\mvnw.cmd dependency:tree -pl libs/grpc-api

# Check for dependency updates
.\mvnw.cmd versions:display-dependency-updates

# Count total tests
.\mvnw.cmd verify 2>&1 | Select-String "Tests run:"
```

---

*Last updated after US-01-06*
