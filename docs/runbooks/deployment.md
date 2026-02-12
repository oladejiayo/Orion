# Deployment Guide

> How to deploy the Orion platform — local development, CI/CD, and AWS environments.

---

## 1. Environments

| Environment | Infrastructure | Purpose |
|------------|---------------|---------|
| **local** | Docker Compose (Redpanda, PostgreSQL, Redis) | Developer workstation |
| **dev** | AWS (small footprint) | Iterative integration testing |
| **staging** | AWS (production-like) | Load tests, demos |
| **prod-demo** | AWS (hardened staging) | Portfolio showcase |

---

## 2. Local Development

### Prerequisites

| Tool | Version | Verify |
|------|---------|--------|
| Java (JDK) | 21+ | `java -version` |
| Maven | 3.9+ (or use wrapper) | `.\mvnw.cmd -version` |
| Docker | 24+ | `docker --version` |
| Docker Compose | v2+ | `docker compose version` |
| Git | 2.40+ | `git --version` |

### Start Local Infrastructure

```powershell
cd infra/docker-compose
docker compose up -d
```

Services available after startup:

| Service | URL | Credentials |
|---------|-----|-------------|
| Redpanda Console | http://localhost:8080 | — |
| pgAdmin | http://localhost:5050 | See `.env` file |
| Redis Commander | http://localhost:8081 | — |
| PostgreSQL | localhost:5432 | See `.env` file |
| Redis | localhost:6379 | — |
| Kafka bootstrap | localhost:19092 | — |

### Build & Test

```powershell
# Full build + tests (390 tests)
.\mvnw.cmd clean verify

# Quick compile (no tests)
.\mvnw.cmd compile

# Single module
.\mvnw.cmd verify -pl libs/grpc-api -am
```

### Stop Local Infrastructure

```powershell
cd infra/docker-compose
docker compose down      # Keep data
docker compose down -v   # Wipe data
```

---

## 3. CI/CD Pipeline (GitHub Actions — Planned)

The CI pipeline will be configured in US-01-07. Planned workflow:

```mermaid
flowchart LR
    PUSH["🔄 Push to main"] --> BUILD["🔨 Build\nmvn compile"]
    BUILD --> TEST["🧪 Test\nmvn verify"]
    TEST --> DOCKER["🐳 Docker Build\nmulti-stage"]
    DOCKER --> PUSH_ECR["📦 Push to ECR"]
    PUSH_ECR --> DEPLOY["🚀 Deploy to ECS"]
```

### Pipeline Stages (Planned)

| Stage | What | Trigger |
|-------|------|---------|
| **Build** | `mvn compile` — compile all modules including proto | Every push |
| **Unit Test** | `mvn test` — run all unit tests | Every push |
| **Integration Test** | `mvn verify -P it` — Testcontainers-based tests | Every push |
| **Docker Build** | Multi-stage Dockerfile (JDK build → JRE runtime) | On merge to main |
| **Push to ECR** | Tag and push container images | On merge to main |
| **Deploy** | ECS service update with new task definition | Manual or on tag |

---

## 4. AWS Deployment (Planned)

### Architecture

```
VPC (Multi-AZ)
├── Public Subnets
│   └── ALB (Application Load Balancer)
├── Private Subnets
│   ├── ECS Fargate (BFF + Domain Services)
│   ├── MSK (Kafka)
│   ├── RDS (PostgreSQL)
│   └── ElastiCache (Redis)
└── S3 (archives, exports)
```

### Infrastructure as Code (Terraform)

Planned module structure:

```
infra/
├── terraform/
│   ├── modules/
│   │   ├── network/        ← VPC, subnets, routing
│   │   ├── compute/        ← ECS cluster, services, ALB
│   │   └── data/           ← MSK, RDS, Redis, S3
│   ├── environments/
│   │   ├── dev/
│   │   ├── staging/
│   │   └── prod-demo/
│   └── main.tf
└── docker-compose/          ← Local dev (exists now)
```

### Docker Image Strategy

Multi-stage builds for minimal image size:

```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
COPY . /app
WORKDIR /app
RUN ./mvnw package -DskipTests -pl services/<name> -am

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
COPY --from=build /app/services/<name>/target/*.jar /app.jar
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

Port convention:
- `8080` — HTTP (REST/WebSocket)
- `9090` — gRPC
- `8081` — Actuator (health, metrics, info)

### Scaling Strategy

| Service | Scale Signal | Notes |
|---------|------------|-------|
| BFF | CPU + WebSocket connections | Stateless, horizontal |
| Market Data Ingest | Kafka consumer lag | Scale with partitions |
| RFQ Service | CPU + RFQ rate | Scale with partitions |
| Execution Service | Low — event-driven | 2-3 instances for HA |
| Post-Trade | Low — event-driven | 2-3 instances for HA |
| Analytics | Kafka lag | Independent scaling |

---

## 5. Deployment Checklist

### Pre-Deploy

- [ ] All tests pass locally (`mvn clean verify`)
- [ ] No dependency vulnerabilities (`mvn dependency-check:check`)
- [ ] Docker image builds successfully
- [ ] Environment variables configured
- [ ] Database migrations up to date

### Post-Deploy

- [ ] Health endpoints returning 200 (`/actuator/health`)
- [ ] Kafka connectivity verified (consumer group registered)
- [ ] gRPC connectivity verified (service descriptor reachable)
- [ ] Metrics flowing to CloudWatch
- [ ] Log aggregation working
- [ ] Smoke test RFQ workflow end-to-end

---

*Last updated after US-01-06*
