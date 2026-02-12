# Operational Runbooks

> Step-by-step guides for local development, debugging, deployment, and incident response.

## Contents

| Runbook | Description |
|---------|-------------|
| [local-development.md](local-development.md) | Prerequisites, clone, build, Docker Compose, IDE setup, common commands |
| [debugging.md](debugging.md) | Build failures, test debugging, Docker issues, correlation tracing, error patterns |
| [deployment.md](deployment.md) | Environments, CI/CD pipeline, AWS architecture, Docker images, scaling |

## Quick Start

```powershell
# 1. Start infrastructure
cd infra/docker-compose ; docker compose up -d

# 2. Build & test (390 tests)
.\mvnw.cmd clean verify

# 3. Stop infrastructure
cd infra/docker-compose ; docker compose down
```

*Last updated after US-01-06*
