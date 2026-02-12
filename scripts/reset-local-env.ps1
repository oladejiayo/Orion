# =============================================================================
# Orion Platform — Reset Local Development Environment (PowerShell)
# =============================================================================
# Stops all containers and REMOVES all data volumes (clean slate).
# Usage: .\scripts\reset-local-env.ps1
# =============================================================================

$ErrorActionPreference = "Stop"

$ComposeFile = "infra\docker-compose\docker-compose.yml"

Write-Host "`n🛑 Stopping all Orion containers and removing data volumes..." -ForegroundColor Red

# Stop containers and remove named volumes
docker compose -f $ComposeFile down -v

Write-Host "`n🧹 Pruning unused Docker networks..." -ForegroundColor Yellow
docker network prune -f 2>$null

Write-Host @"

✅ Local environment reset complete!

To start fresh, run:
  .\scripts\start-local-env.ps1
"@ -ForegroundColor Green
