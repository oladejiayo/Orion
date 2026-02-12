# User Story: US-02-04 - Implement Token Refresh Flow

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-02-04 |
| **Epic** | Epic 02 - Authentication, Sessions & Identity |
| **Title** | Implement Token Refresh Flow |
| **Priority** | P0 - Critical |
| **Story Points** | 3 |

## User Story

**As a** user  
**I want** my session to refresh automatically before token expiry  
**So that** I don't get logged out during active use of the platform

## Acceptance Criteria

- [ ] `POST /auth/refresh` exchanges refresh token for new access token
- [ ] Refresh happens automatically before access token expires
- [ ] Invalid refresh token returns 401 and forces re-login
- [ ] Refresh token rotation supported (optional)
- [ ] Token refresh is transparent to user

## Technical Details

```typescript
// POST /auth/refresh
router.post('/refresh', async (req, res) => {
  const { refreshToken } = req.body;
  
  if (!refreshToken) {
    return res.status(400).json({ error: 'missing_refresh_token' });
  }
  
  try {
    const client = await getOIDCClient();
    const tokenSet = await client.refresh(refreshToken);
    
    res.json({
      accessToken: tokenSet.access_token,
      refreshToken: tokenSet.refresh_token, // May be rotated
      expiresIn: tokenSet.expires_in,
    });
  } catch (error) {
    return res.status(401).json({ error: 'invalid_refresh_token' });
  }
});
```

## Definition of Done

- [ ] Refresh endpoint works
- [ ] Frontend can refresh proactively
- [ ] Expired refresh token handled correctly
- [ ] Tests pass
