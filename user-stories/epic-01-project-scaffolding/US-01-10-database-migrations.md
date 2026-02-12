# User Story: US-01-10 - Setup Database Migration Framework

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-10 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Setup Database Migration Framework |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **Type** | Technical Foundation |

## User Story

**As a** developer  
**I want** a database migration framework with version control for schema changes  
**So that** database schemas can be evolved safely and consistently across environments

## Description

This story establishes the database migration framework using a tool like node-pg-migrate or Prisma Migrate. The framework will support versioned migrations, rollbacks, seed data, and CI integration.

## Acceptance Criteria

### AC1: Migration Tool Setup
- [ ] Migration tool installed and configured
- [ ] Migration scripts directory created
- [ ] Configuration for multiple databases
- [ ] Environment-specific configuration

### AC2: Migration Commands
- [ ] `npm run db:migrate` runs pending migrations
- [ ] `npm run db:migrate:down` rolls back last migration
- [ ] `npm run db:migrate:create <name>` creates new migration
- [ ] `npm run db:migrate:status` shows migration status

### AC3: Seed Data
- [ ] `npm run db:seed` runs seed scripts
- [ ] Seed data for development environment
- [ ] Reference data for all environments

### AC4: CI Integration
- [ ] Migrations run automatically in CI
- [ ] Migration validation in PR checks
- [ ] Database reset for test runs

### AC5: Multi-Database Support
- [ ] Migrations organized by service/database
- [ ] Can run migrations for specific database
- [ ] Parallel-safe migration execution

### AC6: Initial Schemas
- [ ] Base tables for tenants, users, audit
- [ ] Outbox table pattern
- [ ] Processed events table

## Technical Details

### Directory Structure

```
/infra/
├── migrations/
│   ├── config.js              # Migration configuration
│   ├── orion/                 # Main database migrations
│   │   ├── 001_initial_schema.sql
│   │   ├── 002_add_audit_log.sql
│   │   └── ...
│   ├── rfq/                   # RFQ service database
│   │   └── ...
│   ├── marketdata/            # Market data database
│   │   └── ...
│   └── seeds/                 # Seed data scripts
│       ├── development/
│       │   ├── 001_tenants.sql
│       │   └── 002_users.sql
│       └── reference/
│           ├── 001_instruments.sql
│           └── 002_venues.sql
└── docker-compose/
```

### Initial Schema Migration (`001_initial_schema.sql`)
```sql
-- Tenants table
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL DEFAULT 'standard',
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    settings JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_status ON tenants(status);

-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    email VARCHAR(255) NOT NULL,
    username VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, email),
    UNIQUE(tenant_id, username)
);

CREATE INDEX idx_users_tenant_id ON users(tenant_id);
CREATE INDEX idx_users_email ON users(email);

-- User roles table
CREATE TABLE user_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, role)
);

CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);

-- User entitlements table
CREATE TABLE user_entitlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    asset_classes TEXT[] DEFAULT '{}',
    instruments TEXT[] DEFAULT '{}',
    venues TEXT[] DEFAULT '{}',
    max_notional DECIMAL(20, 4),
    rfq_rate_limit INTEGER DEFAULT 10,
    order_rate_limit INTEGER DEFAULT 100,
    max_open_orders INTEGER DEFAULT 100,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id)
);

-- Outbox events table (for reliable event publishing)
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(255) NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    tenant_id UUID NOT NULL,
    correlation_id UUID,
    entity_type VARCHAR(100),
    entity_id VARCHAR(255),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    retry_count INTEGER DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) 
    WHERE published_at IS NULL;
CREATE INDEX idx_outbox_tenant ON outbox_events(tenant_id);

-- Processed events table (for idempotency)
CREATE TABLE processed_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    consumer_group VARCHAR(255) NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(tenant_id, consumer_group, event_id)
);

CREATE INDEX idx_processed_events_lookup 
    ON processed_events(tenant_id, consumer_group, event_id);

-- Audit log table (append-only)
CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    user_id UUID,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    old_value JSONB,
    new_value JSONB,
    correlation_id UUID,
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_tenant ON audit_log(tenant_id);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_log_created ON audit_log(created_at);
CREATE INDEX idx_audit_log_correlation ON audit_log(correlation_id);

-- Trigger for updated_at
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tenants_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER user_entitlements_updated_at
    BEFORE UPDATE ON user_entitlements
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
```

### Migration Configuration (`config.js`)
```javascript
module.exports = {
  orion: {
    databaseUrl: process.env.DATABASE_URL || 'postgres://orion:orion_dev_password@localhost:5432/orion',
    migrationsDir: 'migrations/orion',
    direction: 'up',
    count: Infinity,
    verbose: true,
  },
  rfq: {
    databaseUrl: process.env.RFQ_DATABASE_URL || 'postgres://orion:orion_dev_password@localhost:5432/orion_rfq',
    migrationsDir: 'migrations/rfq',
    direction: 'up',
    count: Infinity,
    verbose: true,
  },
  marketdata: {
    databaseUrl: process.env.MARKETDATA_DATABASE_URL || 'postgres://orion:orion_dev_password@localhost:5432/orion_marketdata',
    migrationsDir: 'migrations/marketdata',
    direction: 'up',
    count: Infinity,
    verbose: true,
  },
};
```

### Package.json Scripts
```json
{
  "scripts": {
    "db:migrate": "node-pg-migrate -m infra/migrations/orion up",
    "db:migrate:down": "node-pg-migrate -m infra/migrations/orion down",
    "db:migrate:create": "node-pg-migrate -m infra/migrations/orion create",
    "db:migrate:status": "node-pg-migrate -m infra/migrations/orion status",
    "db:migrate:all": "npm run db:migrate:orion && npm run db:migrate:rfq && npm run db:migrate:marketdata",
    "db:migrate:orion": "node-pg-migrate -m infra/migrations/orion up",
    "db:migrate:rfq": "DATABASE_URL=$RFQ_DATABASE_URL node-pg-migrate -m infra/migrations/rfq up",
    "db:migrate:marketdata": "DATABASE_URL=$MARKETDATA_DATABASE_URL node-pg-migrate -m infra/migrations/marketdata up",
    "db:seed": "psql $DATABASE_URL -f infra/migrations/seeds/development/001_tenants.sql",
    "db:reset": "npm run db:drop && npm run db:create && npm run db:migrate:all && npm run db:seed"
  }
}
```

## Implementation Steps

1. Install migration tool (node-pg-migrate)
2. Create migration directory structure
3. Configure migration for multiple databases
4. Create initial schema migration
5. Create seed data scripts
6. Add npm scripts
7. Test migrations up/down
8. Document migration workflow

## Definition of Done

- [ ] All acceptance criteria met
- [ ] Migrations run without errors
- [ ] Rollback works correctly
- [ ] Seed data loads correctly
- [ ] CI integration works
- [ ] Documentation complete

## Dependencies

- US-01-02: Docker Compose (for database)

## Notes

- Use sequential numbering for migrations (001, 002, etc.)
- Always test rollback before committing
- Never modify committed migrations; create new ones
- Keep migrations idempotent where possible
