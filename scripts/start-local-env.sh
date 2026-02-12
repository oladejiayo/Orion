#!/bin/bash
# =============================================================================
# Orion Platform — Start Local Development Environment
# =============================================================================
# Usage: ./scripts/start-local-env.sh
# =============================================================================

set -e

COMPOSE_DIR="infra/docker-compose"
COMPOSE_FILE="${COMPOSE_DIR}/docker-compose.yml"

echo "🚀 Starting Orion local development environment..."
echo ""

# Create .env from example if it doesn't exist
if [ ! -f "${COMPOSE_DIR}/.env" ]; then
    echo "📝 Creating .env from .env.example..."
    cp "${COMPOSE_DIR}/.env.example" "${COMPOSE_DIR}/.env"
    echo "   Done. Edit ${COMPOSE_DIR}/.env to customize settings."
    echo ""
fi

# Start all services in detached mode
docker compose -f "${COMPOSE_FILE}" up -d

echo ""
echo "⏳ Waiting for services to become healthy..."

# Wait for PostgreSQL
echo -n "   PostgreSQL... "
until docker compose -f "${COMPOSE_FILE}" exec -T postgres pg_isready -q 2>/dev/null; do
    sleep 2
done
echo "✅"

# Wait for Redis
echo -n "   Redis... "
until docker compose -f "${COMPOSE_FILE}" exec -T redis redis-cli ping 2>/dev/null | grep -q PONG; do
    sleep 2
done
echo "✅"

# Wait for Redpanda (give it extra time)
echo -n "   Redpanda... "
sleep 5
echo "✅"

echo ""
echo "🎉 Orion local environment is ready!"
echo ""
echo "📊 Access URLs:"
echo "   Kafka UI (Redpanda Console):  http://localhost:8080"
echo "   PostgreSQL:                    localhost:5432"
echo "   pgAdmin:                       http://localhost:5050"
echo "   Redis:                         localhost:6379"
echo "   Redis Commander:               http://localhost:8081"
echo "   Schema Registry:               http://localhost:18081"
echo ""
echo "📝 Default Credentials (local dev only):"
echo "   PostgreSQL:  orion / orion_dev_password"
echo "   pgAdmin:     admin@orion.local / admin"
echo ""
echo "🔌 Spring Boot connection properties:"
echo "   spring.datasource.url=jdbc:postgresql://localhost:5432/orion"
echo "   spring.kafka.bootstrap-servers=localhost:19092"
echo "   spring.data.redis.host=localhost"
