# Docker Compose — Local Development Environment

> Complete local infrastructure for Orion development: Redpanda (Kafka-compatible), PostgreSQL, Redis, and admin UIs.

---

## Prerequisites

| Tool | Minimum Version | Verify Command |
|------|----------------|----------------|
| **Docker** | 20.10+ | `docker --version` |
| **Docker Compose** | 2.20+ (V2 plugin) | `docker compose version` |

### System Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| **RAM** | 8 GB | 16 GB |
| **CPU** | 4 cores | 8 cores |
| **Disk** | 10 GB free | 20 GB free |

> Redpanda is configured to use 1 GB RAM + 1 CPU core. PostgreSQL and Redis use defaults. Total Docker memory overhead is approximately **3–4 GB**.

---

## Quick Start

```bash
# From the project root (Orion/)

# Option 1: Use the helper script
./scripts/start-local-env.sh        # Linux/macOS
.\scripts\start-local-env.ps1       # Windows PowerShell

# Option 2: Start directly
docker compose -f infra/docker-compose/docker-compose.yml up -d
```

### First Run

On first run, create a `.env` file from the template:

```bash
cp infra/docker-compose/.env.example infra/docker-compose/.env
```

The helper scripts do this automatically if `.env` doesn't exist.

---

## Port Mappings

| Service | External Port | URL / Connection String | Purpose |
|---------|--------------|------------------------|---------|
| **Redpanda (Kafka)** | 19092 | `localhost:19092` | Kafka bootstrap server |
| **Redpanda (Schema Registry)** | 18081 | `http://localhost:18081` | Schema management |
| **Redpanda (HTTP Proxy)** | 18082 | `http://localhost:18082` | REST Proxy |
| **Redpanda Console** | 8080 | [http://localhost:8080](http://localhost:8080) | Kafka UI |
| **PostgreSQL** | 5432 | `jdbc:postgresql://localhost:5432/orion` | Database |
| **pgAdmin** | 5050 | [http://localhost:5050](http://localhost:5050) | Database UI |
| **Redis** | 6379 | `localhost:6379` | Cache |
| **Redis Commander** | 8081 | [http://localhost:8081](http://localhost:8081) | Redis UI |

### Spring Boot Connection Properties

Use these in `application-local.yml` for any Orion service:

```yaml
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

---

## Common Commands

All commands run from the project root (`Orion/`):

```bash
# Start all services (background)
docker compose -f infra/docker-compose/docker-compose.yml up -d

# Start all services (foreground — see logs)
docker compose -f infra/docker-compose/docker-compose.yml up

# Stop all services (data preserved in volumes)
docker compose -f infra/docker-compose/docker-compose.yml down

# Stop all services and DELETE all data
docker compose -f infra/docker-compose/docker-compose.yml down -v

# View running services
docker compose -f infra/docker-compose/docker-compose.yml ps

# Follow logs for a specific service
docker compose -f infra/docker-compose/docker-compose.yml logs -f postgres

# Restart a single service
docker compose -f infra/docker-compose/docker-compose.yml restart redis

# Execute command in a running container
docker compose -f infra/docker-compose/docker-compose.yml exec postgres psql -U orion -d orion
```

> **Tip:** If you `cd infra/docker-compose/` first, you can use shorter commands:
> ```bash
> docker compose up -d      # start
> docker compose down        # stop
> docker compose down -v     # stop + wipe data
> ```

---

## Default Credentials (Local Dev Only)

| Service | Username / Email | Password |
|---------|-----------------|----------|
| **PostgreSQL** (superuser) | `orion` | `orion_dev_password` |
| **PostgreSQL** (app user) | `orion_app` | `orion_app_password` |
| **pgAdmin** | `admin@orion.local` | `admin` |

> ⚠️ These are **local development only** credentials. Production uses secrets management (AWS Secrets Manager / Azure Key Vault).

---

## Databases

The PostgreSQL init script (`init-scripts/postgres/01-init-databases.sql`) creates these databases on first start:

| Database | Service |
|----------|---------|
| `orion` | Default / shared |
| `orion_rfq` | RFQ Service |
| `orion_marketdata` | Market Data Services |
| `orion_posttrade` | Post-Trade Service |
| `orion_analytics` | Analytics Service |

---

## Data Persistence

- **PostgreSQL**, **Redis**, and **Redpanda** data are stored in **named Docker volumes**.
- Data survives `docker compose down` (containers stopped, volumes kept).
- To wipe all data: `docker compose down -v` or use the reset script.

### Reset Everything

```bash
./scripts/reset-local-env.sh        # Linux/macOS
.\scripts\reset-local-env.ps1       # Windows PowerShell
```

This stops all containers, removes all data volumes, and prunes unused networks.

---

## File Structure

```
infra/docker-compose/
├── docker-compose.yml              # Main compose file
├── .env.example                    # Environment variable template
├── .env                            # Local overrides (gitignored)
├── README.md                       # This file
└── init-scripts/
    └── postgres/
        └── 01-init-databases.sql   # Creates databases + app user
```

---

## Troubleshooting

### Port already in use

```
Error: bind: address already in use
```

Find and stop the process using the port:

```bash
# Linux/macOS
lsof -i :5432
kill -9 <PID>

# Windows PowerShell
netstat -ano | findstr :5432
Stop-Process -Id <PID> -Force
```

### Container won't start / health check failing

```bash
# Check container logs
docker compose -f infra/docker-compose/docker-compose.yml logs postgres

# Restart the failing service
docker compose -f infra/docker-compose/docker-compose.yml restart postgres
```

### PostgreSQL init script didn't run

Init scripts only run when the data volume is **empty** (first start). If you've previously started PostgreSQL, the init scripts won't re-run.

**Fix:** Remove the volume and start fresh:

```bash
docker compose -f infra/docker-compose/docker-compose.yml down -v
docker compose -f infra/docker-compose/docker-compose.yml up -d
```

### Docker out of memory

Increase Docker Desktop memory allocation to at least **8 GB**:
- Docker Desktop → Settings → Resources → Memory

### Cannot connect from Spring Boot application

Ensure you're using the **external** ports (listed in the port mapping table above), not the internal container ports. For Kafka, use `localhost:19092`, not `localhost:9092`.

---

*Created for US-01-02 — Docker Compose Local Development Environment*
