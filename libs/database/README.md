# Orion :: Libs :: Database

Database migration framework for the Orion platform using **Flyway 10.x**.

> **Reinterpretation (US-01-10):** The original story specified `node-pg-migrate` (TypeScript).
> In the Java ecosystem, Flyway is the standard equivalent — it uses the same `.sql` migration
> files with versioned naming, integrates natively with Spring Boot, and provides Maven plugin
> commands that replace the original `npm` scripts.

## Quick Start

### Run migrations (main Orion database)

```bash
# Via Maven plugin (requires running PostgreSQL)
mvn flyway:migrate -pl libs/database

# Via Spring Boot auto-configuration (when service starts)
# Migrations run automatically if orion.flyway.orion.enabled=true
```

### Check migration status

```bash
mvn flyway:info -pl libs/database
```

### Reset database (development only!)

```bash
mvn flyway:clean flyway:migrate -pl libs/database
```

### Run with seed data

```bash
mvn flyway:migrate -pl libs/database -Pflyway-seed
```

## Migration Commands (npm → Maven mapping)

| Original (TypeScript) | Java Equivalent | Description |
|---|---|---|
| `npm run db:migrate` | `mvn flyway:migrate -pl libs/database` | Run pending migrations |
| `npm run db:migrate:down` | `mvn flyway:clean -pl libs/database` | Reset database (dev) |
| `npm run db:migrate:create` | Create `V{n}__{name}.sql` manually | New migration file |
| `npm run db:migrate:status` | `mvn flyway:info -pl libs/database` | Show migration status |
| `npm run db:seed` | `mvn flyway:migrate -pl libs/database -Pflyway-seed` | Load seed data |
| `npm run db:migrate:all` | Three separate `-P` profile runs | Migrate all databases |

## Multi-Database Support

The Orion platform uses separate databases for different services:

| Database | Maven Profile | Purpose |
|---|---|---|
| `orion` | *(default)* | Main database: tenants, users, audit, outbox |
| `orion_rfq` | `-Pflyway-rfq` | RFQ service (US-07-01) |
| `orion_marketdata` | `-Pflyway-marketdata` | Market data service (US-06-01) |

```bash
# Migrate RFQ database
mvn flyway:migrate -pl libs/database -Pflyway-rfq

# Migrate market data database
mvn flyway:migrate -pl libs/database -Pflyway-marketdata
```

## Directory Structure

```
libs/database/
├── pom.xml                                     # Flyway deps + Maven plugin
├── README.md                                   # This file
└── src/
    ├── main/
    │   ├── java/com/orion/database/
    │   │   ├── package-info.java               # Package documentation
    │   │   └── migration/
    │   │       ├── package-info.java
    │   │       ├── FlywayConfigProperties.java  # @ConfigurationProperties record
    │   │       ├── FlywayMultiDatabaseConfig.java # Multi-DB Flyway bean setup
    │   │       └── MigrationService.java        # Status/utility service
    │   └── resources/
    │       ├── application-flyway.yml           # Flyway config (activate with profile)
    │       └── db/
    │           ├── migration/
    │           │   ├── orion/                   # Main database migrations
    │           │   │   ├── V1__initial_schema.sql
    │           │   │   └── V2__initial_triggers.sql
    │           │   ├── rfq/                     # RFQ service (placeholder)
    │           │   └── marketdata/              # Market data service (placeholder)
    │           └── seed/
    │               ├── development/             # Dev-only seed data
    │               │   ├── V1000__seed_tenants.sql
    │               │   └── V1001__seed_users.sql
    │               └── reference/               # All-environment reference data
    │                   ├── R__reference_instruments.sql
    │                   └── R__reference_venues.sql
    └── test/
        ├── java/com/orion/database/migration/
        │   ├── FlywayConfigPropertiesTest.java
        │   ├── FlywayMultiDatabaseConfigTest.java
        │   ├── MigrationResourceTest.java
        │   └── MigrationServiceTest.java
        └── resources/
            └── application-test.yml
```

## Flyway Naming Conventions

| Pattern | Example | Purpose |
|---|---|---|
| `V{n}__description.sql` | `V1__initial_schema.sql` | Versioned migration (runs once) |
| `V{1000+}__seed_*.sql` | `V1000__seed_tenants.sql` | Seed data (high version, dev only) |
| `R__description.sql` | `R__reference_instruments.sql` | Repeatable migration (re-runs on change) |

## Initial Schema (V1)

The initial schema creates these tables:

| Table | Purpose |
|---|---|
| `tenants` | Multi-tenant organizations |
| `users` | User accounts (tenant-scoped) |
| `user_roles` | RBAC role assignments |
| `user_entitlements` | Trading permissions & rate limits |
| `outbox_events` | Transactional outbox for reliable events |
| `processed_events` | Idempotent event consumption |
| `audit_log` | Append-only audit trail |

## Configuration

Add to your service's `application.yml`:

```yaml
spring:
  profiles:
    include: flyway

orion:
  flyway:
    orion:
      enabled: true
```

Or use environment variables:

```bash
ORION_DB_URL=jdbc:postgresql://myhost:5432/orion
ORION_DB_USER=orion
ORION_DB_PASSWORD=secret
```
