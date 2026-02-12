# User Story: US-03-02 - Database Row-Level Security

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-03-02 |
| **Epic** | Epic 03 - Multi-Tenancy |
| **Title** | Database Row-Level Security (RLS) |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **PRD Reference** | FR-TENANT-02 |

## User Story

**As a** security architect  
**I want** PostgreSQL Row-Level Security enforced on all tenant-scoped tables  
**So that** cross-tenant data access is impossible at the database level

## Description

Implement comprehensive Row-Level Security (RLS) policies on all multi-tenant tables. This provides defense-in-depth by enforcing tenant isolation at the database layer, preventing any possibility of cross-tenant data access regardless of application bugs or SQL injection attempts.

## Acceptance Criteria

- [ ] All tenant-scoped tables have RLS enabled
- [ ] RLS policies filter by `app.tenant_id` session variable
- [ ] Superuser/admin bypass available for operations
- [ ] Migration validates RLS on all tables
- [ ] Performance impact < 5%
- [ ] Cross-tenant SELECT returns zero rows
- [ ] Cross-tenant INSERT/UPDATE/DELETE rejected

## Technical Details

### RLS Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Application Layer                        │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐         │
│  │ BFF Service │    │ RFQ Service │    │Trade Service│         │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘         │
│         │                  │                  │                 │
│         └──────────────────┼──────────────────┘                 │
│                            │                                    │
│                   SET app.tenant_id = 'xxx'                     │
│                            │                                    │
└────────────────────────────┼────────────────────────────────────┘
                             │
┌────────────────────────────┼────────────────────────────────────┐
│                    PostgreSQL Database                          │
│                            │                                    │
│  ┌─────────────────────────▼─────────────────────────────────┐  │
│  │                    RLS Policy Check                        │  │
│  │         WHERE tenant_id = current_setting('app.tenant_id') │  │
│  └─────────────────────────┬─────────────────────────────────┘  │
│                            │                                    │
│  ┌─────────────────────────▼─────────────────────────────────┐  │
│  │                      Table Data                            │  │
│  │   tenant_id │ id │ data...                                 │  │
│  │   ─────────────────────────                                │  │
│  │   tenant-a  │ 1  │ ...    ← Only visible to tenant-a      │  │
│  │   tenant-b  │ 2  │ ...    ← Only visible to tenant-b      │  │
│  └────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Base RLS Migration

```sql
-- Migration: 003_enable_rls_infrastructure.sql

-- Create helper function to get current tenant
CREATE OR REPLACE FUNCTION current_tenant_id() 
RETURNS UUID AS $$
BEGIN
  RETURN NULLIF(current_setting('app.tenant_id', true), '')::UUID;
EXCEPTION
  WHEN invalid_text_representation THEN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- Create helper function for admin bypass check
CREATE OR REPLACE FUNCTION is_admin_context()
RETURNS BOOLEAN AS $$
BEGIN
  RETURN COALESCE(current_setting('app.is_admin', true)::BOOLEAN, false);
EXCEPTION
  WHEN OTHERS THEN
    RETURN false;
END;
$$ LANGUAGE plpgsql STABLE;

-- Template policy creation function
CREATE OR REPLACE FUNCTION create_tenant_rls_policy(table_name TEXT)
RETURNS VOID AS $$
BEGIN
  -- Enable RLS
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
  
  -- Force RLS for table owner too (important for security)
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
  
  -- Create SELECT policy
  EXECUTE format(
    'CREATE POLICY %I_tenant_select ON %I FOR SELECT USING (
      tenant_id = current_tenant_id() OR is_admin_context()
    )',
    table_name, table_name
  );
  
  -- Create INSERT policy
  EXECUTE format(
    'CREATE POLICY %I_tenant_insert ON %I FOR INSERT WITH CHECK (
      tenant_id = current_tenant_id() OR is_admin_context()
    )',
    table_name, table_name
  );
  
  -- Create UPDATE policy
  EXECUTE format(
    'CREATE POLICY %I_tenant_update ON %I FOR UPDATE USING (
      tenant_id = current_tenant_id() OR is_admin_context()
    ) WITH CHECK (
      tenant_id = current_tenant_id() OR is_admin_context()
    )',
    table_name, table_name
  );
  
  -- Create DELETE policy
  EXECUTE format(
    'CREATE POLICY %I_tenant_delete ON %I FOR DELETE USING (
      tenant_id = current_tenant_id() OR is_admin_context()
    )',
    table_name, table_name
  );
END;
$$ LANGUAGE plpgsql;
```

### Apply RLS to Tables

```sql
-- Migration: 004_apply_rls_to_tables.sql

-- Users table
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;

CREATE POLICY users_tenant_isolation ON users
  FOR ALL USING (
    tenant_id = current_tenant_id() OR is_admin_context()
  )
  WITH CHECK (
    tenant_id = current_tenant_id() OR is_admin_context()
  );

-- Instruments table
SELECT create_tenant_rls_policy('instruments');

-- Counterparties table
SELECT create_tenant_rls_policy('counterparties');

-- RFQs table
SELECT create_tenant_rls_policy('rfqs');

-- Quotes table
SELECT create_tenant_rls_policy('quotes');

-- Trades table
SELECT create_tenant_rls_policy('trades');

-- Orders table
SELECT create_tenant_rls_policy('orders');

-- Audit log table
SELECT create_tenant_rls_policy('audit_log');

-- Add more tables as they are created...
```

### Tenant Context Database Pool

```typescript
// libs/database/src/tenant-pool.ts
import { Pool, PoolClient } from 'pg';
import { AsyncLocalStorage } from 'async_hooks';

interface TenantContext {
  tenantId: string;
  userId?: string;
  isAdmin?: boolean;
}

const tenantStorage = new AsyncLocalStorage<TenantContext>();

export class TenantAwarePool {
  private pool: Pool;

  constructor(connectionString: string) {
    this.pool = new Pool({
      connectionString,
      max: 20,
      idleTimeoutMillis: 30000,
      connectionTimeoutMillis: 2000,
    });
  }

  /**
   * Get a client with tenant context automatically set
   */
  async getClient(): Promise<PoolClient> {
    const context = tenantStorage.getStore();
    const client = await this.pool.connect();

    if (context?.tenantId) {
      await client.query(`SET app.tenant_id = $1`, [context.tenantId]);
    }
    
    if (context?.isAdmin) {
      await client.query(`SET app.is_admin = true`);
    }

    return client;
  }

  /**
   * Execute query within tenant context
   */
  async query<T>(sql: string, params?: unknown[]): Promise<T[]> {
    const client = await this.getClient();
    try {
      const result = await client.query(sql, params);
      return result.rows as T[];
    } finally {
      client.release();
    }
  }

  /**
   * Execute transaction within tenant context
   */
  async transaction<T>(fn: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.getClient();
    try {
      await client.query('BEGIN');
      const result = await fn(client);
      await client.query('COMMIT');
      return result;
    } catch (error) {
      await client.query('ROLLBACK');
      throw error;
    } finally {
      client.release();
    }
  }

  /**
   * Run callback within tenant context
   */
  static runWithTenant<T>(context: TenantContext, fn: () => T): T {
    return tenantStorage.run(context, fn);
  }

  /**
   * Run callback in admin context (bypasses RLS)
   */
  static runAsAdmin<T>(fn: () => T): T {
    return tenantStorage.run({ tenantId: '', isAdmin: true }, fn);
  }

  /**
   * Get current tenant context
   */
  static getCurrentTenant(): TenantContext | undefined {
    return tenantStorage.getStore();
  }
}

export { tenantStorage, TenantContext };
```

### Repository Base Class

```typescript
// libs/database/src/base.repository.ts
import { TenantAwarePool } from './tenant-pool';

export abstract class TenantScopedRepository<T> {
  constructor(
    protected readonly pool: TenantAwarePool,
    protected readonly tableName: string,
  ) {}

  /**
   * All queries automatically filtered by RLS
   */
  async findById(id: string): Promise<T | null> {
    const rows = await this.pool.query<T>(
      `SELECT * FROM ${this.tableName} WHERE id = $1`,
      [id]
    );
    return rows[0] || null;
  }

  async findAll(filters?: Record<string, unknown>): Promise<T[]> {
    let sql = `SELECT * FROM ${this.tableName}`;
    const params: unknown[] = [];
    
    if (filters && Object.keys(filters).length > 0) {
      const conditions = Object.entries(filters)
        .map(([key, _], idx) => `${key} = $${idx + 1}`);
      sql += ` WHERE ${conditions.join(' AND ')}`;
      params.push(...Object.values(filters));
    }
    
    return this.pool.query<T>(sql, params);
  }

  async create(data: Partial<T>): Promise<T> {
    const context = TenantAwarePool.getCurrentTenant();
    if (!context?.tenantId && !context?.isAdmin) {
      throw new Error('Tenant context required for insert');
    }

    // tenant_id is automatically validated by RLS WITH CHECK
    const dataWithTenant = { ...data, tenant_id: context.tenantId };
    
    const keys = Object.keys(dataWithTenant);
    const values = Object.values(dataWithTenant);
    const placeholders = keys.map((_, i) => `$${i + 1}`);

    const sql = `
      INSERT INTO ${this.tableName} (${keys.join(', ')})
      VALUES (${placeholders.join(', ')})
      RETURNING *
    `;

    const rows = await this.pool.query<T>(sql, values);
    return rows[0];
  }

  async update(id: string, data: Partial<T>): Promise<T> {
    const entries = Object.entries(data);
    const setClause = entries.map(([key, _], idx) => `${key} = $${idx + 2}`);
    
    const sql = `
      UPDATE ${this.tableName}
      SET ${setClause.join(', ')}, updated_at = NOW()
      WHERE id = $1
      RETURNING *
    `;

    const rows = await this.pool.query<T>(sql, [id, ...entries.map(e => e[1])]);
    if (rows.length === 0) {
      throw new Error(`Record not found or not accessible: ${id}`);
    }
    return rows[0];
  }

  async delete(id: string): Promise<boolean> {
    const sql = `DELETE FROM ${this.tableName} WHERE id = $1`;
    const result = await this.pool.query(sql, [id]);
    return (result as any).rowCount > 0;
  }

  /**
   * Count records (respects RLS)
   */
  async count(filters?: Record<string, unknown>): Promise<number> {
    let sql = `SELECT COUNT(*) as count FROM ${this.tableName}`;
    const params: unknown[] = [];
    
    if (filters && Object.keys(filters).length > 0) {
      const conditions = Object.entries(filters)
        .map(([key, _], idx) => `${key} = $${idx + 1}`);
      sql += ` WHERE ${conditions.join(' AND ')}`;
      params.push(...Object.values(filters));
    }
    
    const rows = await this.pool.query<{ count: string }>(sql, params);
    return parseInt(rows[0].count, 10);
  }
}
```

### RLS Validation Migration

```sql
-- Migration: 005_validate_rls_coverage.sql
-- This migration validates that all tenant-scoped tables have RLS enabled

DO $$
DECLARE
  missing_rls TEXT[];
  table_record RECORD;
BEGIN
  -- Find tables with tenant_id column but no RLS
  SELECT array_agg(t.tablename) INTO missing_rls
  FROM pg_tables t
  JOIN information_schema.columns c 
    ON c.table_name = t.tablename AND c.table_schema = t.schemaname
  LEFT JOIN pg_class cls ON cls.relname = t.tablename
  WHERE t.schemaname = 'public'
    AND c.column_name = 'tenant_id'
    AND NOT cls.relrowsecurity;

  IF array_length(missing_rls, 1) > 0 THEN
    RAISE EXCEPTION 'Tables with tenant_id but missing RLS: %', missing_rls;
  END IF;

  -- Verify all tenant tables have policies
  FOR table_record IN
    SELECT DISTINCT t.tablename
    FROM pg_tables t
    JOIN information_schema.columns c 
      ON c.table_name = t.tablename AND c.table_schema = t.schemaname
    WHERE t.schemaname = 'public'
      AND c.column_name = 'tenant_id'
  LOOP
    IF NOT EXISTS (
      SELECT 1 FROM pg_policies WHERE tablename = table_record.tablename
    ) THEN
      RAISE EXCEPTION 'Table % has RLS enabled but no policies defined', table_record.tablename;
    END IF;
  END LOOP;

  RAISE NOTICE 'RLS validation passed for all tenant-scoped tables';
END;
$$;
```

### CI Check for RLS Coverage

```typescript
// scripts/validate-rls.ts
import { Pool } from 'pg';

interface TableInfo {
  tablename: string;
  has_tenant_id: boolean;
  rls_enabled: boolean;
  policy_count: number;
}

async function validateRLS() {
  const pool = new Pool({
    connectionString: process.env.DATABASE_URL,
  });

  try {
    const result = await pool.query<TableInfo>(`
      SELECT 
        t.tablename,
        EXISTS (
          SELECT 1 FROM information_schema.columns c 
          WHERE c.table_name = t.tablename 
            AND c.column_name = 'tenant_id'
        ) as has_tenant_id,
        COALESCE(cls.relrowsecurity, false) as rls_enabled,
        (SELECT COUNT(*) FROM pg_policies p WHERE p.tablename = t.tablename) as policy_count
      FROM pg_tables t
      LEFT JOIN pg_class cls ON cls.relname = t.tablename
      WHERE t.schemaname = 'public'
        AND t.tablename NOT IN ('schema_migrations', 'spatial_ref_sys')
      ORDER BY t.tablename
    `);

    const issues: string[] = [];

    for (const table of result.rows) {
      if (table.has_tenant_id && !table.rls_enabled) {
        issues.push(`❌ ${table.tablename}: Has tenant_id but RLS not enabled`);
      } else if (table.has_tenant_id && table.policy_count === 0) {
        issues.push(`❌ ${table.tablename}: Has RLS enabled but no policies`);
      } else if (table.has_tenant_id) {
        console.log(`✅ ${table.tablename}: RLS enabled with ${table.policy_count} policies`);
      } else {
        console.log(`ℹ️  ${table.tablename}: Not tenant-scoped (no tenant_id)`);
      }
    }

    if (issues.length > 0) {
      console.error('\n🚨 RLS Validation Failed:');
      issues.forEach(issue => console.error(issue));
      process.exit(1);
    }

    console.log('\n✅ RLS validation passed for all tables');
  } finally {
    await pool.end();
  }
}

validateRLS().catch(console.error);
```

## Implementation Steps

1. **Create RLS infrastructure migration**
   - Add helper functions
   - Create policy template function
   - Document usage patterns

2. **Apply RLS to existing tables**
   - Enable RLS on each table
   - Create comprehensive policies
   - Test each table individually

3. **Build tenant-aware pool**
   - Implement AsyncLocalStorage context
   - Auto-set session variables
   - Provide admin bypass

4. **Create base repository**
   - Implement CRUD with RLS awareness
   - Ensure tenant context propagation
   - Add validation checks

5. **Add CI validation**
   - Create validation script
   - Add to CI pipeline
   - Fail on missing coverage

6. **Performance testing**
   - Benchmark with/without RLS
   - Optimize indexes
   - Document results

## Definition of Done

- [ ] All tenant tables have RLS enabled
- [ ] Policies created for SELECT, INSERT, UPDATE, DELETE
- [ ] Helper functions deployed
- [ ] Tenant-aware pool implemented
- [ ] CI validation passes
- [ ] Performance benchmark completed
- [ ] Cross-tenant access impossible

## Dependencies

- **US-03-01**: Tenant Registration (tenants table exists)
- **US-01-10**: Database Migration Framework

## Performance Considerations

```sql
-- Essential indexes for RLS performance
CREATE INDEX CONCURRENTLY idx_users_tenant_id ON users(tenant_id);
CREATE INDEX CONCURRENTLY idx_instruments_tenant_id ON instruments(tenant_id);
CREATE INDEX CONCURRENTLY idx_rfqs_tenant_id ON rfqs(tenant_id);
CREATE INDEX CONCURRENTLY idx_trades_tenant_id ON trades(tenant_id);
CREATE INDEX CONCURRENTLY idx_orders_tenant_id ON orders(tenant_id);

-- Composite indexes for common queries
CREATE INDEX CONCURRENTLY idx_rfqs_tenant_status ON rfqs(tenant_id, status);
CREATE INDEX CONCURRENTLY idx_trades_tenant_date ON trades(tenant_id, executed_at DESC);
```

## Test Cases

```typescript
describe('Row-Level Security', () => {
  let tenantAPool: TenantAwarePool;
  let tenantBPool: TenantAwarePool;
  const tenantAId = 'tenant-a-uuid';
  const tenantBId = 'tenant-b-uuid';

  beforeEach(async () => {
    // Set up test data for both tenants (using admin context)
    await TenantAwarePool.runAsAdmin(async () => {
      await pool.query(
        `INSERT INTO instruments (id, tenant_id, symbol) VALUES 
         ('inst-1', $1, 'AAPL'),
         ('inst-2', $2, 'GOOG')`,
        [tenantAId, tenantBId]
      );
    });
  });

  describe('SELECT isolation', () => {
    it('should only return records for current tenant', async () => {
      const results = await TenantAwarePool.runWithTenant(
        { tenantId: tenantAId },
        () => pool.query('SELECT * FROM instruments')
      );
      
      expect(results).toHaveLength(1);
      expect(results[0].symbol).toBe('AAPL');
    });

    it('should return zero rows for other tenant data', async () => {
      const results = await TenantAwarePool.runWithTenant(
        { tenantId: tenantAId },
        () => pool.query(`SELECT * FROM instruments WHERE id = 'inst-2'`)
      );
      
      expect(results).toHaveLength(0);
    });
  });

  describe('INSERT isolation', () => {
    it('should set tenant_id automatically via context', async () => {
      await TenantAwarePool.runWithTenant(
        { tenantId: tenantAId },
        () => pool.query(
          `INSERT INTO instruments (symbol, tenant_id) VALUES ('MSFT', $1)`,
          [tenantAId]
        )
      );
      
      // Verify via admin context
      const result = await TenantAwarePool.runAsAdmin(
        () => pool.query(`SELECT tenant_id FROM instruments WHERE symbol = 'MSFT'`)
      );
      expect(result[0].tenant_id).toBe(tenantAId);
    });

    it('should reject INSERT with different tenant_id', async () => {
      await expect(
        TenantAwarePool.runWithTenant(
          { tenantId: tenantAId },
          () => pool.query(
            `INSERT INTO instruments (symbol, tenant_id) VALUES ('TSLA', $1)`,
            [tenantBId] // Wrong tenant!
          )
        )
      ).rejects.toThrow(); // RLS WITH CHECK fails
    });
  });

  describe('UPDATE isolation', () => {
    it('should only update own tenant records', async () => {
      const result = await TenantAwarePool.runWithTenant(
        { tenantId: tenantAId },
        () => pool.query(
          `UPDATE instruments SET symbol = 'AAPL-UPDATED' WHERE id = 'inst-2' RETURNING *`
        )
      );
      
      // inst-2 belongs to tenant B, so no rows updated
      expect(result).toHaveLength(0);
    });
  });

  describe('DELETE isolation', () => {
    it('should not delete other tenant records', async () => {
      await TenantAwarePool.runWithTenant(
        { tenantId: tenantAId },
        () => pool.query(`DELETE FROM instruments WHERE id = 'inst-2'`)
      );
      
      // Verify inst-2 still exists
      const exists = await TenantAwarePool.runAsAdmin(
        () => pool.query(`SELECT 1 FROM instruments WHERE id = 'inst-2'`)
      );
      expect(exists).toHaveLength(1);
    });
  });

  describe('Admin bypass', () => {
    it('should allow admin to see all tenant data', async () => {
      const results = await TenantAwarePool.runAsAdmin(
        () => pool.query('SELECT * FROM instruments')
      );
      
      expect(results).toHaveLength(2);
    });
  });
});
```

## Security Considerations

1. **Never use string interpolation for tenant_id** - always use parameterized queries
2. **Force RLS even for table owner** - prevents bypassing via superuser connection
3. **Validate tenant context exists** before any database operation
4. **Log failed RLS checks** for security monitoring
5. **Regular audits** of RLS policy coverage

## Notes

- RLS policies are evaluated per-row, so index on `tenant_id` is critical
- Use `FORCE ROW LEVEL SECURITY` to prevent bypass by table owner
- Consider partitioning by `tenant_id` for very large tables
- Admin bypass should be audit-logged
