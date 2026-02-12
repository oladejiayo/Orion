# Epic 03: Multi-Tenancy

## Epic Overview

| Field | Value |
|-------|-------|
| **Epic ID** | EPIC-03 |
| **Epic Name** | Multi-Tenancy |
| **Priority** | P0 - Critical |
| **Target Release** | MVP |
| **PRD Reference** | FR-TENANT-01 through FR-TENANT-04 |

## Description

Implement comprehensive multi-tenancy architecture ensuring complete data isolation between tenants. Every data access, query, and operation must be scoped to the authenticated user's tenant. This epic covers tenant registration, database-level isolation with Row-Level Security (RLS), tenant-aware service middleware, and tenant lifecycle management.

## Business Value

- **Data Security**: Guarantee complete isolation between competing institutions
- **Compliance**: Meet regulatory requirements for data segregation
- **Scalability**: Support onboarding of new institutional clients
- **Governance**: Provide tenant-specific configurations and policies

## User Stories

| Story ID | Title | Priority | Points |
|----------|-------|----------|--------|
| US-03-01 | Tenant Registration Flow | P0 | 5 |
| US-03-02 | Database Row-Level Security | P0 | 8 |
| US-03-03 | Tenant Context Middleware | P0 | 5 |
| US-03-04 | Tenant Configuration Service | P1 | 5 |
| US-03-05 | Tenant Data Seeding | P1 | 3 |
| US-03-06 | Tenant Audit and Compliance | P1 | 5 |

## Technical Scope

### Multi-Tenancy Model
- **Isolation Strategy**: Shared database, shared schema with RLS
- **Tenant Identifier**: UUID in JWT claims (`tenant_id`)
- **Context Propagation**: Request-scoped tenant context
- **Query Enforcement**: Automatic tenant filtering via RLS policies

### Key Components
1. **Tenant Service**: Manages tenant lifecycle
2. **RLS Policies**: PostgreSQL policies on all tenant-scoped tables
3. **Tenant Middleware**: Extracts and validates tenant context
4. **Configuration Store**: Per-tenant settings and preferences

### Database Design Pattern
```sql
-- Every tenant-scoped table includes:
CREATE TABLE example_table (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id),
  -- other columns...
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- RLS policy pattern
ALTER TABLE example_table ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON example_table
  USING (tenant_id = current_setting('app.tenant_id')::UUID);
```

## Success Criteria

1. ✅ No cross-tenant data leakage possible
2. ✅ All queries automatically filtered by tenant
3. ✅ Tenant context available in all service layers
4. ✅ New tenants can be onboarded without code changes
5. ✅ Audit trail captures tenant context
6. ✅ Performance overhead < 5% from RLS

## Dependencies

- **Epic 01**: Project Scaffolding (shared libraries)
- **Epic 02**: Authentication (JWT with tenant claims)

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| RLS bypass through raw SQL | Critical | Code review, parameterized queries only |
| Missing tenant_id on new tables | High | Migration validation, CI checks |
| Performance degradation | Medium | Index on tenant_id, query analysis |
| Tenant context lost in async | Medium | AsyncLocalStorage propagation |

## Acceptance Criteria (Epic Level)

- [ ] All tables have tenant_id column and RLS policies
- [ ] Middleware validates and propagates tenant context
- [ ] Cross-tenant queries impossible even with raw SQL
- [ ] Tenant configuration stored and retrievable
- [ ] Audit logs capture tenant for every operation
- [ ] Integration tests prove isolation
