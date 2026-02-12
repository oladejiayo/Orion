# User Story: US-03-05 - Tenant Data Seeding

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-03-05 |
| **Epic** | Epic 03 - Multi-Tenancy |
| **Title** | Tenant Data Seeding |
| **Priority** | P1 - High |
| **Story Points** | 3 |

## User Story

**As a** platform operator  
**I want** new tenants automatically seeded with initial data  
**So that** they can start using the platform immediately

## Description

When a new tenant is onboarded, automatically provision essential reference data, default users, and sample configurations. This ensures tenants have a working environment from day one.

## Acceptance Criteria

- [ ] Seed script triggered on `tenant.activated` event
- [ ] Default admin user created
- [ ] Sample instruments seeded
- [ ] Default counterparties created
- [ ] Seeding is idempotent
- [ ] Progress reported via events

## Technical Details

### Seed Data Configuration

```typescript
// services/tenant-service/src/application/seed-data.ts
export const DEFAULT_SEED_DATA = {
  // Default instruments for new tenants
  instruments: [
    { symbol: 'BTCUSD', name: 'Bitcoin/USD', assetClass: 'crypto', tickSize: 0.01 },
    { symbol: 'ETHUSD', name: 'Ethereum/USD', assetClass: 'crypto', tickSize: 0.01 },
    { symbol: 'EUR/USD', name: 'Euro/US Dollar', assetClass: 'fx', tickSize: 0.00001 },
    { symbol: 'GBP/USD', name: 'British Pound/US Dollar', assetClass: 'fx', tickSize: 0.00001 },
    { symbol: 'AAPL', name: 'Apple Inc.', assetClass: 'equity', tickSize: 0.01 },
    { symbol: 'GOOGL', name: 'Alphabet Inc.', assetClass: 'equity', tickSize: 0.01 },
  ],
  
  // Sample counterparties
  counterparties: [
    { name: 'Internal', code: 'INTERNAL', type: 'internal' },
    { name: 'Sample LP 1', code: 'SAMPLE_LP1', type: 'liquidity_provider' },
    { name: 'Sample LP 2', code: 'SAMPLE_LP2', type: 'liquidity_provider' },
  ],
  
  // Default user roles
  roles: ['tenant_admin', 'trader', 'analyst', 'viewer'],
};
```

### Seeding Service

```typescript
// services/tenant-service/src/application/tenant-seeder.service.ts
import { Injectable, OnModuleInit } from '@nestjs/common';
import { EventBus } from '@orion/event-model';
import { TenantAwarePool } from '@orion/database';
import { logger } from '@orion/observability';
import { DEFAULT_SEED_DATA } from './seed-data';

@Injectable()
export class TenantSeederService implements OnModuleInit {
  constructor(
    private readonly pool: TenantAwarePool,
    private readonly eventBus: EventBus,
  ) {}

  async onModuleInit() {
    await this.eventBus.subscribe('tenant.activated', async (event) => {
      await this.seedTenant(event.payload.tenantId);
    });
  }

  async seedTenant(tenantId: string): Promise<void> {
    logger.info('Starting tenant seeding', { tenantId });

    await this.eventBus.publish({
      eventType: 'tenant.seeding.started',
      aggregateType: 'tenant',
      aggregateId: tenantId,
      payload: { tenantId },
      metadata: { tenantId, correlationId: crypto.randomUUID() },
    });

    try {
      await TenantAwarePool.runWithTenant({ tenantId, isAdmin: true }, async () => {
        await this.seedInstruments(tenantId);
        await this.seedCounterparties(tenantId);
        await this.seedDefaultRoles(tenantId);
      });

      await this.eventBus.publish({
        eventType: 'tenant.seeding.completed',
        aggregateType: 'tenant',
        aggregateId: tenantId,
        payload: { tenantId, success: true },
        metadata: { tenantId, correlationId: crypto.randomUUID() },
      });

      logger.info('Tenant seeding completed', { tenantId });
    } catch (error) {
      logger.error('Tenant seeding failed', { tenantId, error });
      
      await this.eventBus.publish({
        eventType: 'tenant.seeding.failed',
        aggregateType: 'tenant',
        aggregateId: tenantId,
        payload: { tenantId, error: (error as Error).message },
        metadata: { tenantId, correlationId: crypto.randomUUID() },
      });
      
      throw error;
    }
  }

  private async seedInstruments(tenantId: string): Promise<void> {
    for (const instrument of DEFAULT_SEED_DATA.instruments) {
      // Idempotent insert
      await this.pool.query(`
        INSERT INTO instruments (tenant_id, symbol, name, asset_class, tick_size, status)
        VALUES ($1, $2, $3, $4, $5, 'active')
        ON CONFLICT (tenant_id, symbol) DO NOTHING
      `, [tenantId, instrument.symbol, instrument.name, instrument.assetClass, instrument.tickSize]);
    }
    logger.info('Instruments seeded', { tenantId, count: DEFAULT_SEED_DATA.instruments.length });
  }

  private async seedCounterparties(tenantId: string): Promise<void> {
    for (const cp of DEFAULT_SEED_DATA.counterparties) {
      await this.pool.query(`
        INSERT INTO counterparties (tenant_id, name, code, type, status)
        VALUES ($1, $2, $3, $4, 'active')
        ON CONFLICT (tenant_id, code) DO NOTHING
      `, [tenantId, cp.name, cp.code, cp.type]);
    }
    logger.info('Counterparties seeded', { tenantId, count: DEFAULT_SEED_DATA.counterparties.length });
  }

  private async seedDefaultRoles(tenantId: string): Promise<void> {
    for (const role of DEFAULT_SEED_DATA.roles) {
      await this.pool.query(`
        INSERT INTO roles (tenant_id, name, permissions)
        VALUES ($1, $2, $3)
        ON CONFLICT (tenant_id, name) DO NOTHING
      `, [tenantId, role, JSON.stringify(this.getDefaultPermissions(role))]);
    }
    logger.info('Roles seeded', { tenantId, count: DEFAULT_SEED_DATA.roles.length });
  }

  private getDefaultPermissions(role: string): string[] {
    const permissionMap: Record<string, string[]> = {
      tenant_admin: ['*'],
      trader: ['rfq:create', 'rfq:read', 'trade:read', 'instrument:read'],
      analyst: ['rfq:read', 'trade:read', 'analytics:read', 'instrument:read'],
      viewer: ['rfq:read', 'trade:read', 'instrument:read'],
    };
    return permissionMap[role] || [];
  }
}
```

### CLI Command

```typescript
// services/tenant-service/src/cli/seed-tenant.command.ts
import { Command, CommandRunner, Option } from 'nest-commander';
import { TenantSeederService } from '../application/tenant-seeder.service';

@Command({ name: 'seed-tenant', description: 'Seed data for a tenant' })
export class SeedTenantCommand extends CommandRunner {
  constructor(private readonly seeder: TenantSeederService) {
    super();
  }

  async run(passedParams: string[], options: { tenantId: string }): Promise<void> {
    console.log(`Seeding tenant: ${options.tenantId}`);
    await this.seeder.seedTenant(options.tenantId);
    console.log('Seeding completed!');
  }

  @Option({ flags: '-t, --tenant-id <tenantId>', required: true })
  parseTenantId(val: string): string {
    return val;
  }
}
```

## Definition of Done

- [ ] Auto-seed on tenant activation
- [ ] Default data created correctly
- [ ] Idempotent seeding works
- [ ] Events published for tracking
- [ ] CLI command available
- [ ] Tests verify seeding

## Dependencies

- **US-03-01**: Tenant Registration
- **US-03-02**: Row-Level Security

## Test Cases

```typescript
describe('TenantSeederService', () => {
  it('should seed instruments on activation', async () => {
    await eventBus.emit({ eventType: 'tenant.activated', payload: { tenantId: 'new-tenant' } });
    
    const instruments = await pool.query('SELECT * FROM instruments WHERE tenant_id = $1', ['new-tenant']);
    expect(instruments.length).toBe(DEFAULT_SEED_DATA.instruments.length);
  });

  it('should be idempotent', async () => {
    await seeder.seedTenant('tenant-1');
    await seeder.seedTenant('tenant-1');
    
    const instruments = await pool.query('SELECT * FROM instruments WHERE tenant_id = $1', ['tenant-1']);
    expect(instruments.length).toBe(DEFAULT_SEED_DATA.instruments.length); // No duplicates
  });
});
```
