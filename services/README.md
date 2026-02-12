# Services

Java Spring Boot microservices. Each subdirectory is a Maven module containing a standalone Spring Boot application.

## Planned Services

| Service | Description | Status |
|---------|-------------|--------|
| `bff-workstation` | Backend-for-Frontend — Workstation API (REST + WebSocket) | Planned |
| `bff-admin` | Backend-for-Frontend — Admin Console API | Planned |
| `marketdata-ingest` | Market data ingestion from sources → Kafka | Planned |
| `marketdata-projection` | Snapshot projector (Kafka → Redis read model) | Planned |
| `rfq-service` | RFQ lifecycle management | Planned |
| `lp-bot-service` | Simulated liquidity provider bot | Planned |
| `execution-service` | Trade execution engine | Planned |
| `posttrade-service` | Post-trade confirmation and settlement | Planned |
| `analytics-service` | TCA-lite, benchmarks, analytics engine | Planned |
| `admin-service` | Reference data and user administration | Planned |

## Convention

Each service follows the standard Maven + Spring Boot layout:

```
services/<service-name>/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/orion/<service>/
│   │   │   ├── domain/          # Entities, value objects, domain events
│   │   │   ├── application/     # Use cases, application services
│   │   │   ├── infrastructure/  # JPA repos, Kafka producers, gRPC clients
│   │   │   └── api/             # REST controllers, gRPC server impls
│   │   └── resources/
│   │       ├── application.yml
│   │       └── application-local.yml
│   └── test/
│       └── java/com/orion/<service>/
```
