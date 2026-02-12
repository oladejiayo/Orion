# User Story: US-02-05 - User Profile Endpoint

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-02-05 |
| **Epic** | Epic 02 - Authentication, Sessions & Identity |
| **Title** | User Profile Endpoint |
| **Priority** | P1 - High |
| **Story Points** | 3 |

## User Story

**As a** user  
**I want** to retrieve my profile information  
**So that** I can see my identity, roles, and permissions in the UI

## Acceptance Criteria

- [ ] `GET /me` returns user profile from JWT claims
- [ ] Response includes userId, email, name, roles
- [ ] Response includes tenant information
- [ ] Response includes entitlements
- [ ] Unauthenticated requests return 401

## Technical Details

### Response Schema
```json
{
  "user": {
    "userId": "uuid",
    "email": "user@example.com",
    "username": "jsmith",
    "displayName": "John Smith"
  },
  "tenant": {
    "tenantId": "tenant-001",
    "tenantName": "Acme Corp"
  },
  "roles": ["ROLE_TRADER"],
  "entitlements": {
    "assetClasses": ["FX", "RATES"],
    "instruments": [],
    "venues": [],
    "limits": {
      "maxNotional": 10000000,
      "rfqRateLimit": 10
    }
  }
}
```

### Implementation
```typescript
router.get('/me', authMiddleware(), (req, res) => {
  const ctx = req.securityContext!;
  res.json({
    user: ctx.user,
    tenant: ctx.tenant,
    roles: ctx.roles,
    entitlements: ctx.entitlements,
  });
});
```

## Definition of Done

- [ ] Endpoint returns correct profile
- [ ] All claims mapped correctly
- [ ] 401 for unauthenticated
- [ ] Tests pass
