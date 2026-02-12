# User Story: US-01-02 - Create Docker Compose Local Development Environment

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-02 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Create Docker Compose Local Development Environment |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **Type** | Technical Foundation |

## User Story

**As a** developer  
**I want** a complete local development environment using Docker Compose  
**So that** I can run all infrastructure dependencies (Kafka, PostgreSQL, Redis) locally without cloud costs and have a consistent environment across the team

## Description

This story creates a comprehensive Docker Compose configuration that replicates the production infrastructure locally. The environment will include Redpanda (Kafka-compatible), PostgreSQL, Redis, and supporting tools like Kafka UI and pgAdmin for debugging. The setup should be optimized for developer productivity with proper health checks, volume persistence, and easy reset capabilities.

## Acceptance Criteria

### AC1: Core Infrastructure Services
- [ ] Redpanda (Kafka-compatible) container is configured and accessible on standard ports
- [ ] PostgreSQL 15 container is configured with health checks
- [ ] Redis 7 container is configured with persistence options
- [ ] All services are on a shared Docker network named `orion-network`
- [ ] Services have appropriate resource limits for development machines

### AC2: Development Tooling Containers
- [ ] Redpanda Console (Kafka UI) is accessible at `http://localhost:8080`
- [ ] pgAdmin is accessible at `http://localhost:5050` for database management
- [ ] Redis Commander or similar is accessible at `http://localhost:8081`

### AC3: Configuration and Secrets
- [ ] Environment variables are externalized to `.env` file (gitignored)
- [ ] `.env.example` file documents all required variables
- [ ] Default credentials are set for local development only
- [ ] Database initialization scripts are in `/infra/docker-compose/init-scripts/`

### AC4: Developer Experience
- [ ] `docker-compose up` starts all services
- [ ] `docker-compose up -d` starts services in background
- [ ] `docker-compose down` stops all services
- [ ] `docker-compose down -v` removes all data volumes
- [ ] Services have proper startup dependencies (wait-for patterns)
- [ ] Health check endpoints are configured for all services

### AC5: Data Persistence
- [ ] PostgreSQL data persists between restarts (named volume)
- [ ] Redis data can optionally persist (configurable)
- [ ] Kafka data persists between restarts (named volume)
- [ ] Reset script exists to clear all data: `scripts/reset-local-env.sh`

### AC6: Documentation
- [ ] README in `/infra/docker-compose/` explains setup
- [ ] Troubleshooting guide for common issues
- [ ] Port mapping documentation
- [ ] Resource requirements documented (RAM, CPU)

## Technical Details

### Port Mappings

| Service | Port | Purpose |
|---------|------|---------|
| Redpanda | 9092 | Kafka protocol |
| Redpanda | 8082 | Schema Registry |
| Redpanda | 8083 | HTTP Proxy |
| Redpanda Console | 8080 | Kafka UI |
| PostgreSQL | 5432 | Database |
| pgAdmin | 5050 | Database UI |
| Redis | 6379 | Cache |
| Redis Commander | 8081 | Redis UI |

### Files to Create

#### `/infra/docker-compose/docker-compose.yml`
```yaml
version: '3.8'

services:
  # ===================
  # MESSAGE BROKER
  # ===================
  redpanda:
    image: docker.redpanda.com/redpandadata/redpanda:v23.3.5
    container_name: orion-redpanda
    command:
      - redpanda
      - start
      - --kafka-addr internal://0.0.0.0:9092,external://0.0.0.0:19092
      - --advertise-kafka-addr internal://redpanda:9092,external://localhost:19092
      - --pandaproxy-addr internal://0.0.0.0:8082,external://0.0.0.0:18082
      - --advertise-pandaproxy-addr internal://redpanda:8082,external://localhost:18082
      - --schema-registry-addr internal://0.0.0.0:8081,external://0.0.0.0:18081
      - --rpc-addr redpanda:33145
      - --advertise-rpc-addr redpanda:33145
      - --smp 1
      - --memory 1G
      - --mode dev-container
      - --default-log-level=warn
    ports:
      - "19092:19092"
      - "18081:18081"
      - "18082:18082"
    volumes:
      - redpanda-data:/var/lib/redpanda/data
    networks:
      - orion-network
    healthcheck:
      test: ["CMD-SHELL", "rpk cluster health | grep -E 'Healthy:.+true' || exit 1"]
      interval: 15s
      timeout: 3s
      retries: 5

  redpanda-console:
    image: docker.redpanda.com/redpandadata/console:v2.4.3
    container_name: orion-redpanda-console
    entrypoint: /bin/sh
    command: -c "echo \"$$CONSOLE_CONFIG_FILE\" > /tmp/config.yml; /app/console"
    environment:
      CONFIG_FILEPATH: /tmp/config.yml
      CONSOLE_CONFIG_FILE: |
        kafka:
          brokers: ["redpanda:9092"]
          schemaRegistry:
            enabled: true
            urls: ["http://redpanda:8081"]
        redpanda:
          adminApi:
            enabled: true
            urls: ["http://redpanda:9644"]
    ports:
      - "8080:8080"
    networks:
      - orion-network
    depends_on:
      redpanda:
        condition: service_healthy

  # ===================
  # DATABASE
  # ===================
  postgres:
    image: postgres:15-alpine
    container_name: orion-postgres
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-orion}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-orion_dev_password}
      POSTGRES_DB: ${POSTGRES_DB:-orion}
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./init-scripts/postgres:/docker-entrypoint-initdb.d
    networks:
      - orion-network
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-orion} -d ${POSTGRES_DB:-orion}"]
      interval: 10s
      timeout: 5s
      retries: 5

  pgadmin:
    image: dpage/pgadmin4:latest
    container_name: orion-pgadmin
    environment:
      PGADMIN_DEFAULT_EMAIL: ${PGADMIN_EMAIL:-admin@orion.local}
      PGADMIN_DEFAULT_PASSWORD: ${PGADMIN_PASSWORD:-admin}
      PGADMIN_CONFIG_SERVER_MODE: "False"
    ports:
      - "5050:80"
    volumes:
      - pgadmin-data:/var/lib/pgadmin
    networks:
      - orion-network
    depends_on:
      postgres:
        condition: service_healthy

  # ===================
  # CACHE
  # ===================
  redis:
    image: redis:7-alpine
    container_name: orion-redis
    command: redis-server --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    networks:
      - orion-network
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis-commander:
    image: rediscommander/redis-commander:latest
    container_name: orion-redis-commander
    environment:
      REDIS_HOSTS: local:redis:6379
    ports:
      - "8081:8081"
    networks:
      - orion-network
    depends_on:
      redis:
        condition: service_healthy

networks:
  orion-network:
    name: orion-network
    driver: bridge

volumes:
  redpanda-data:
    name: orion-redpanda-data
  postgres-data:
    name: orion-postgres-data
  pgadmin-data:
    name: orion-pgadmin-data
  redis-data:
    name: orion-redis-data
```

#### `/infra/docker-compose/.env.example`
```bash
# PostgreSQL Configuration
POSTGRES_USER=orion
POSTGRES_PASSWORD=orion_dev_password
POSTGRES_DB=orion

# pgAdmin Configuration
PGADMIN_EMAIL=admin@orion.local
PGADMIN_PASSWORD=admin

# Redis Configuration (optional overrides)
# REDIS_PASSWORD=optional_password

# Redpanda Configuration (optional overrides)
# REDPANDA_BROKER_ID=0
```

#### `/infra/docker-compose/init-scripts/postgres/01-init-databases.sql`
```sql
-- Create additional databases for different services if needed
CREATE DATABASE orion_rfq;
CREATE DATABASE orion_marketdata;
CREATE DATABASE orion_posttrade;
CREATE DATABASE orion_analytics;

-- Create application user with limited privileges
CREATE USER orion_app WITH PASSWORD 'orion_app_password';

-- Grant privileges to app user on all databases
GRANT ALL PRIVILEGES ON DATABASE orion TO orion_app;
GRANT ALL PRIVILEGES ON DATABASE orion_rfq TO orion_app;
GRANT ALL PRIVILEGES ON DATABASE orion_marketdata TO orion_app;
GRANT ALL PRIVILEGES ON DATABASE orion_posttrade TO orion_app;
GRANT ALL PRIVILEGES ON DATABASE orion_analytics TO orion_app;

-- Extension for UUID generation
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
```

#### `/scripts/reset-local-env.sh`
```bash
#!/bin/bash

# Orion Local Environment Reset Script
# This script stops all containers and removes all data volumes

set -e

echo "🛑 Stopping all Orion containers..."
docker-compose -f infra/docker-compose/docker-compose.yml down

echo "🗑️  Removing all data volumes..."
docker-compose -f infra/docker-compose/docker-compose.yml down -v

echo "🧹 Pruning unused Docker resources..."
docker network prune -f

echo "✅ Local environment reset complete!"
echo ""
echo "To start fresh, run:"
echo "  docker-compose -f infra/docker-compose/docker-compose.yml up -d"
```

#### `/scripts/start-local-env.sh`
```bash
#!/bin/bash

# Orion Local Environment Start Script

set -e

echo "🚀 Starting Orion local development environment..."

# Check if .env exists, create from example if not
if [ ! -f "infra/docker-compose/.env" ]; then
  echo "📝 Creating .env from .env.example..."
  cp infra/docker-compose/.env.example infra/docker-compose/.env
fi

# Start services
docker-compose -f infra/docker-compose/docker-compose.yml up -d

echo ""
echo "⏳ Waiting for services to be healthy..."

# Wait for PostgreSQL
until docker-compose -f infra/docker-compose/docker-compose.yml exec -T postgres pg_isready; do
  sleep 2
done
echo "✅ PostgreSQL is ready"

# Wait for Redis
until docker-compose -f infra/docker-compose/docker-compose.yml exec -T redis redis-cli ping | grep -q PONG; do
  sleep 2
done
echo "✅ Redis is ready"

# Wait for Redpanda
sleep 5  # Give Redpanda extra time
echo "✅ Redpanda is ready"

echo ""
echo "🎉 Orion local environment is ready!"
echo ""
echo "📊 Access URLs:"
echo "  - Kafka UI (Redpanda Console): http://localhost:8080"
echo "  - PostgreSQL:                  localhost:5432"
echo "  - pgAdmin:                     http://localhost:5050"
echo "  - Redis:                       localhost:6379"
echo "  - Redis Commander:             http://localhost:8081"
echo ""
echo "📝 Credentials (local dev only):"
echo "  - PostgreSQL: orion / orion_dev_password"
echo "  - pgAdmin:    admin@orion.local / admin"
```

### Implementation Steps

1. **Create Directory Structure**
   ```bash
   mkdir -p infra/docker-compose/init-scripts/postgres
   mkdir -p scripts
   ```

2. **Create Docker Compose File**
   - Create main `docker-compose.yml`
   - Verify YAML syntax

3. **Create Environment Files**
   - Create `.env.example` with documented variables
   - Add `.env` to `.gitignore`

4. **Create Initialization Scripts**
   - Create PostgreSQL init scripts
   - Make scripts executable

5. **Create Helper Scripts**
   - Create `start-local-env.sh`
   - Create `reset-local-env.sh`
   - Make scripts executable

6. **Test Complete Setup**
   - Run `docker-compose up`
   - Verify all services start
   - Test connections to all services
   - Verify UIs are accessible

7. **Create Documentation**
   - Document port mappings
   - Document troubleshooting steps
   - Document system requirements

### System Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| RAM | 8 GB | 16 GB |
| CPU | 4 cores | 8 cores |
| Disk | 10 GB free | 20 GB free |
| Docker | 20.10+ | Latest |

## Definition of Done

- [ ] All acceptance criteria are met
- [ ] `docker-compose up` starts all services without errors
- [ ] All services pass health checks
- [ ] All UI tools are accessible
- [ ] Data persists between restarts
- [ ] Reset script works correctly
- [ ] Documentation is complete
- [ ] Code has been reviewed and approved

## Dependencies

- US-01-01: Initialize Monorepo (for directory structure)

## Testing Requirements

### Manual Testing
1. Run `./scripts/start-local-env.sh`
2. Verify all containers are running: `docker ps`
3. Access Redpanda Console at http://localhost:8080
4. Access pgAdmin at http://localhost:5050
5. Connect to PostgreSQL: `psql -h localhost -U orion -d orion`
6. Connect to Redis: `redis-cli -h localhost`
7. Stop and restart: `docker-compose down && docker-compose up -d`
8. Verify data persists
9. Run reset script and verify clean state

### Automated Testing
- Create health check script that verifies all services
- Add to CI pipeline as optional smoke test

## Notes

- Redpanda is used instead of Apache Kafka for lighter resource usage
- All ports are mapped to localhost for easy access
- Production will use AWS MSK, not Redpanda
- Consider adding Localstack for AWS service simulation in future

## Related Documentation

- [Redpanda Documentation](https://docs.redpanda.com/)
- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [PostgreSQL Docker](https://hub.docker.com/_/postgres)
- [Redis Docker](https://hub.docker.com/_/redis)
