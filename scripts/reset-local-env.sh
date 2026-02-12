#!/bin/bash
# =============================================================================
# Orion Platform — Reset Local Development Environment
# =============================================================================
# Stops all containers and REMOVES all data volumes (clean slate).
# Usage: ./scripts/reset-local-env.sh
# =============================================================================

set -e

COMPOSE_FILE="infra/docker-compose/docker-compose.yml"

echo "🛑 Stopping all Orion containers and removing data volumes..."
echo ""

# Stop containers and remove named volumes
docker compose -f "${COMPOSE_FILE}" down -v

echo ""
echo "🧹 Pruning unused Docker networks..."
docker network prune -f 2>/dev/null || true

echo ""
echo "✅ Local environment reset complete!"
echo ""
echo "To start fresh, run:"
echo "  ./scripts/start-local-env.sh"
