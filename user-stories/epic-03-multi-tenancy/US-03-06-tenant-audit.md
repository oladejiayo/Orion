# User Story: US-03-06 - Tenant Audit and Compliance

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-03-06 |
| **Epic** | Epic 03 - Multi-Tenancy |
| **Title** | Tenant Audit and Compliance |
| **Priority** | P1 - High |
| **Story Points** | 5 |

## User Story

**As a** compliance officer  
**I want** all tenant operations audited  
**So that** we can meet regulatory requirements and investigate issues

## Description

Implement comprehensive audit logging for all tenant operations with tenant context. Every data mutation, configuration change, and significant access must be recorded with full context for regulatory compliance.

## Acceptance Criteria

- [ ] Audit log captures all CRUD operations
- [ ] Tenant context always recorded
- [ ] User identity and IP captured
- [ ] Immutable audit trail
- [ ] Queryable by date, user, entity
- [ ] Retention configurable per tenant
- [ ] Export capability for regulators

## Technical Details

### Audit Log Schema

```sql
-- Migration: 006_create_audit_log.sql
CREATE TABLE audit_log (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  user_id UUID,
  action VARCHAR(50) NOT NULL,
  entity_type VARCHAR(100) NOT NULL,
  entity_id UUID,
  old_value JSONB,
  new_value JSONB,
  ip_address INET,
  user_agent TEXT,
  correlation_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Enable RLS
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;

CREATE POLICY audit_log_tenant_isolation ON audit_log
  FOR ALL USING (tenant_id = current_tenant_id() OR is_admin_context());

-- Indexes
CREATE INDEX idx_audit_log_tenant_created ON audit_log(tenant_id, created_at DESC);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_log_user ON audit_log(tenant_id, user_id, created_at DESC);

-- Prevent updates/deletes (immutable)
CREATE RULE audit_log_no_update AS ON UPDATE TO audit_log DO INSTEAD NOTHING;
CREATE RULE audit_log_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING;
```

### Audit Service

```typescript
// libs/observability/src/audit/audit.service.ts
import { Injectable } from '@nestjs/common';
import { TenantAwarePool } from '@orion/database';
import { getCurrentTenant } from '@orion/security';
import { Request } from 'express';

export interface AuditEntry {
  action: 'CREATE' | 'UPDATE' | 'DELETE' | 'READ' | 'LOGIN' | 'LOGOUT' | 'EXPORT' | 'CONFIG_CHANGE';
  entityType: string;
  entityId?: string;
  oldValue?: unknown;
  newValue?: unknown;
  ipAddress?: string;
  userAgent?: string;
  correlationId?: string;
}

@Injectable()
export class AuditService {
  constructor(private readonly pool: TenantAwarePool) {}

  async log(entry: AuditEntry, req?: Request): Promise<void> {
    const context = getCurrentTenant();
    
    if (!context) {
      throw new Error('Audit requires tenant context');
    }

    await this.pool.query(`
      INSERT INTO audit_log (
        tenant_id, user_id, action, entity_type, entity_id,
        old_value, new_value, ip_address, user_agent, correlation_id
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
    `, [
      context.tenantId,
      context.userId,
      entry.action,
      entry.entityType,
      entry.entityId,
      entry.oldValue ? JSON.stringify(entry.oldValue) : null,
      entry.newValue ? JSON.stringify(entry.newValue) : null,
      req?.ip || entry.ipAddress,
      req?.headers['user-agent'] || entry.userAgent,
      context.correlationId || entry.correlationId,
    ]);
  }

  async query(params: {
    startDate?: Date;
    endDate?: Date;
    userId?: string;
    entityType?: string;
    action?: string;
    limit?: number;
    offset?: number;
  }): Promise<AuditLogEntry[]> {
    const conditions: string[] = [];
    const values: unknown[] = [];
    let paramIndex = 1;

    if (params.startDate) {
      conditions.push(`created_at >= $${paramIndex++}`);
      values.push(params.startDate);
    }
    if (params.endDate) {
      conditions.push(`created_at <= $${paramIndex++}`);
      values.push(params.endDate);
    }
    if (params.userId) {
      conditions.push(`user_id = $${paramIndex++}`);
      values.push(params.userId);
    }
    if (params.entityType) {
      conditions.push(`entity_type = $${paramIndex++}`);
      values.push(params.entityType);
    }
    if (params.action) {
      conditions.push(`action = $${paramIndex++}`);
      values.push(params.action);
    }

    const where = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';
    const limit = params.limit || 100;
    const offset = params.offset || 0;

    return this.pool.query(`
      SELECT * FROM audit_log ${where}
      ORDER BY created_at DESC
      LIMIT ${limit} OFFSET ${offset}
    `, values);
  }

  async export(tenantId: string, startDate: Date, endDate: Date): Promise<AuditLogEntry[]> {
    // Log the export itself
    await this.log({
      action: 'EXPORT',
      entityType: 'AUDIT_LOG',
      newValue: { startDate, endDate },
    });

    return this.query({ startDate, endDate, limit: 100000 });
  }
}
```

### Audit Decorator

```typescript
// libs/observability/src/audit/audit.decorator.ts
import { applyDecorators, SetMetadata, UseInterceptors } from '@nestjs/common';
import { AuditInterceptor } from './audit.interceptor';

export function Audited(entityType: string, action?: string) {
  return applyDecorators(
    SetMetadata('audit_entity_type', entityType),
    SetMetadata('audit_action', action),
    UseInterceptors(AuditInterceptor),
  );
}

// Usage:
// @Post()
// @Audited('RFQ', 'CREATE')
// async createRfq(@Body() dto: CreateRfqDto) { ... }
```

### API Endpoints

```typescript
// services/tenant-service/src/api/audit.controller.ts
@Controller('audit')
@UseGuards(TenantAdminGuard)
export class AuditController {
  constructor(private readonly auditService: AuditService) {}

  @Get()
  async queryAuditLog(
    @Query('startDate') startDate?: string,
    @Query('endDate') endDate?: string,
    @Query('userId') userId?: string,
    @Query('entityType') entityType?: string,
    @Query('action') action?: string,
    @Query('limit') limit?: number,
    @Query('offset') offset?: number,
  ) {
    return this.auditService.query({
      startDate: startDate ? new Date(startDate) : undefined,
      endDate: endDate ? new Date(endDate) : undefined,
      userId,
      entityType,
      action,
      limit,
      offset,
    });
  }

  @Get('export')
  async exportAuditLog(
    @Query('startDate') startDate: string,
    @Query('endDate') endDate: string,
    @CurrentTenant('tenantId') tenantId: string,
    @Res() res: Response,
  ) {
    const data = await this.auditService.export(
      tenantId,
      new Date(startDate),
      new Date(endDate),
    );
    
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Content-Disposition', `attachment; filename=audit-${tenantId}-${startDate}-${endDate}.json`);
    res.send(JSON.stringify(data, null, 2));
  }
}
```

## Definition of Done

- [ ] Audit table created with RLS
- [ ] All CRUD operations logged
- [ ] Immutable audit trail enforced
- [ ] Query API functional
- [ ] Export capability works
- [ ] Decorator simplifies usage
- [ ] Tests verify audit capture

## Dependencies

- **US-03-02**: Row-Level Security
- **US-03-03**: Tenant Context Middleware

## Test Cases

```typescript
describe('AuditService', () => {
  it('should capture audit entry with tenant context', async () => {
    await runWithTenant({ tenantId: 'test-tenant', userId: 'user-1' }, async () => {
      await auditService.log({
        action: 'CREATE',
        entityType: 'RFQ',
        entityId: 'rfq-123',
        newValue: { symbol: 'BTCUSD', quantity: 100 },
      });
    });
    
    const logs = await runAsAdmin(() => 
      pool.query('SELECT * FROM audit_log WHERE entity_id = $1', ['rfq-123'])
    );
    
    expect(logs[0].tenant_id).toBe('test-tenant');
    expect(logs[0].user_id).toBe('user-1');
    expect(logs[0].action).toBe('CREATE');
  });

  it('should prevent audit log modifications', async () => {
    await expect(
      pool.query('DELETE FROM audit_log WHERE id = $1', ['some-id'])
    ).resolves.not.toThrow(); // Silent no-op due to rule
    
    // Verify record still exists
    const exists = await pool.query('SELECT 1 FROM audit_log WHERE id = $1', ['some-id']);
    expect(exists.length).toBe(1);
  });
});
```
