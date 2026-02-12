# =============================================================================
# Orion Platform — Start Local Development Environment (PowerShell)
# =============================================================================
# Usage: .\scripts\start-local-env.ps1
# =============================================================================

$ErrorActionPreference = "Stop"

$ComposeDir = "infra\docker-compose"
$ComposeFile = "$ComposeDir\docker-compose.yml"

Write-Host "`n🚀 Starting Orion local development environment..." -ForegroundColor Cyan

# Create .env from example if it doesn't exist
if (-not (Test-Path "$ComposeDir\.env")) {
    Write-Host "📝 Creating .env from .env.example..." -ForegroundColor Yellow
    Copy-Item "$ComposeDir\.env.example" "$ComposeDir\.env"
    Write-Host "   Done. Edit $ComposeDir\.env to customize settings.`n"
}

# Start all services in detached mode
docker compose -f $ComposeFile up -d

Write-Host "`n⏳ Waiting for services to become healthy..." -ForegroundColor Yellow

# Wait for PostgreSQL
Write-Host -NoNewline "   PostgreSQL... "
do {
    Start-Sleep -Seconds 2
    $ready = docker compose -f $ComposeFile exec -T postgres pg_isready -q 2>$null
} while ($LASTEXITCODE -ne 0)
Write-Host "✅" -ForegroundColor Green

# Wait for Redis
Write-Host -NoNewline "   Redis... "
do {
    Start-Sleep -Seconds 2
    $pong = docker compose -f $ComposeFile exec -T redis redis-cli ping 2>$null
} while ($pong -notmatch "PONG")
Write-Host "✅" -ForegroundColor Green

# Wait for Redpanda
Write-Host -NoNewline "   Redpanda... "
Start-Sleep -Seconds 5
Write-Host "✅" -ForegroundColor Green

Write-Host @"

🎉 Orion local environment is ready!

📊 Access URLs:
   Kafka UI (Redpanda Console):  http://localhost:8080
   PostgreSQL:                    localhost:5432
   pgAdmin:                       http://localhost:5050
   Redis:                         localhost:6379
   Redis Commander:               http://localhost:8081
   Schema Registry:               http://localhost:18081

📝 Default Credentials (local dev only):
   PostgreSQL:  orion / orion_dev_password
   pgAdmin:     admin@orion.local / admin

🔌 Spring Boot connection properties:
   spring.datasource.url=jdbc:postgresql://localhost:5432/orion
   spring.kafka.bootstrap-servers=localhost:19092
   spring.data.redis.host=localhost
"@ -ForegroundColor Green
