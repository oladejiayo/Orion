# User Story: US-02-06 - Session Timeout and Logout

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-02-06 |
| **Epic** | Epic 02 - Authentication, Sessions & Identity |
| **Title** | Session Timeout and Logout |
| **Priority** | P1 - High |
| **Story Points** | 3 |

## User Story

**As a** user  
**I want** my session to timeout after inactivity and logout securely  
**So that** my account is protected when I'm away

## Acceptance Criteria

- [ ] `POST /auth/logout` invalidates session
- [ ] Frontend clears tokens on logout
- [ ] Session timeout configurable (default 30 min)
- [ ] OIDC logout endpoint called (optional)
- [ ] Logout event audited

## Technical Details

```typescript
router.post('/logout', async (req, res) => {
  const ctx = req.securityContext;
  
  // Log audit event
  await auditService.log({
    action: 'LOGOUT',
    userId: ctx?.user.userId,
    tenantId: ctx?.tenant.tenantId,
  });
  
  // Clear server session if using cookies
  req.session?.destroy(() => {});
  
  // Return logout URL for frontend to redirect
  res.json({
    success: true,
    logoutUrl: `${config.issuerUrl}/protocol/openid-connect/logout`,
  });
});
```

## Definition of Done

- [ ] Logout endpoint works
- [ ] Session cleared correctly
- [ ] Audit event logged
- [ ] Frontend handles logout
- [ ] Tests pass
