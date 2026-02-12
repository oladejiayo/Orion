# User Story: US-02-07 - Authentication Event Auditing

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-02-07 |
| **Epic** | Epic 02 - Authentication, Sessions & Identity |
| **Title** | Authentication Event Auditing |
| **Priority** | P0 - Critical |
| **Story Points** | 3 |

## User Story

**As a** security administrator  
**I want** all authentication events logged  
**So that** I can audit access and detect suspicious activity

## Acceptance Criteria

- [ ] Login success events logged
- [ ] Login failure events logged
- [ ] Logout events logged
- [ ] Token refresh events logged
- [ ] IP address and user agent captured
- [ ] Events stored in audit_log table

## Technical Details

### Audit Events
| Event | Data Captured |
|-------|---------------|
| LOGIN_SUCCESS | userId, email, tenantId, ip, userAgent, timestamp |
| LOGIN_FAILURE | email, reason, ip, userAgent, timestamp |
| LOGOUT | userId, tenantId, ip, timestamp |
| TOKEN_REFRESH | userId, tenantId, timestamp |
| TOKEN_INVALID | reason, ip, timestamp |

### Implementation
```typescript
interface AuthAuditEvent {
  action: 'LOGIN_SUCCESS' | 'LOGIN_FAILURE' | 'LOGOUT' | 'TOKEN_REFRESH' | 'TOKEN_INVALID';
  userId?: string;
  email?: string;
  tenantId?: string;
  reason?: string;
  ipAddress: string;
  userAgent: string;
  correlationId?: string;
}

async function auditAuthEvent(event: AuthAuditEvent): Promise<void> {
  await db.query(
    `INSERT INTO audit_log (tenant_id, user_id, action, entity_type, entity_id, new_value, ip_address, user_agent)
     VALUES ($1, $2, $3, 'AUTH', $4, $5, $6, $7)`,
    [event.tenantId, event.userId, event.action, event.userId || 'anonymous', 
     JSON.stringify(event), event.ipAddress, event.userAgent]
  );
}
```

## Definition of Done

- [ ] All auth events audited
- [ ] Data captured correctly
- [ ] Query performance acceptable
- [ ] Tests verify audit trail
