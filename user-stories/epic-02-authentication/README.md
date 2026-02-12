# Epic 02: Authentication, Sessions & Identity

## Epic Overview

| Field | Value |
|-------|-------|
| **Epic ID** | EPIC-02 |
| **Epic Name** | Authentication, Sessions & Identity |
| **Priority** | P0 - Critical |
| **Target Milestone** | MVP |
| **Estimated Effort** | 2 Sprints |

## Business Context

Orion requires a robust authentication and identity management system to secure all platform operations. This epic implements OIDC-based authentication using AWS Cognito (or Keycloak for local development), JWT token handling, session management, and user profile management. Security is a non-negotiable requirement for an institutional trading platform.

## Epic Goals

1. **OIDC Authentication:** Implement OpenID Connect authentication flow with JWT tokens
2. **Session Management:** Handle token refresh, session timeout, and secure logout
3. **User Identity:** Manage user profiles, roles, and basic identity information
4. **Security Audit:** Log all authentication events for compliance
5. **Developer Experience:** Provide easy local development authentication setup

## Success Criteria

- [ ] Users can authenticate via OIDC login flow
- [ ] JWT tokens contain required claims (roles, tenant, entitlements)
- [ ] Token refresh works silently without user intervention
- [ ] Admin can disable users with immediate effect
- [ ] All auth events are audited
- [ ] Local development works with Keycloak

## Dependencies

- **Requires:** Epic 01 (Project Scaffolding) - shared libraries, service template
- **Enables:** All other epics requiring authentication

## User Stories in This Epic

| Story ID | Story Title | Priority | Points |
|----------|-------------|----------|--------|
| US-02-01 | Setup Local Keycloak for Development | P0 | 3 |
| US-02-02 | Implement OIDC Login Flow in BFF | P0 | 5 |
| US-02-03 | JWT Token Validation and Claims Extraction | P0 | 5 |
| US-02-04 | Implement Token Refresh Flow | P0 | 3 |
| US-02-05 | User Profile Endpoint | P1 | 3 |
| US-02-06 | Session Timeout and Logout | P1 | 3 |
| US-02-07 | Authentication Event Auditing | P0 | 3 |
| US-02-08 | AWS Cognito Configuration (Production) | P1 | 5 |

## Technical Scope

### Authentication Flow
```
User → Login Page → OIDC Provider (Cognito/Keycloak)
                          ↓
                    Auth Code → Token Exchange
                          ↓
                    Access Token (JWT) + Refresh Token
                          ↓
                    BFF validates JWT → Extracts claims
                          ↓
                    SecurityContext attached to request
```

### JWT Claims Structure
```json
{
  "sub": "user-uuid",
  "email": "user@example.com",
  "preferred_username": "username",
  "tenant_id": "tenant-001",
  "roles": ["ROLE_TRADER", "ROLE_ANALYST"],
  "entitlements": {
    "asset_classes": ["FX", "RATES"],
    "instruments": [],
    "venues": []
  },
  "iat": 1707500000,
  "exp": 1707503600,
  "iss": "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_xxx",
  "aud": "orion-client-id"
}
```

## Risks & Mitigations

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| JWKS endpoint unavailability | Low | High | Cache JWKS keys locally with refresh |
| Token expiry during operation | Medium | Medium | Proactive token refresh before expiry |
| Session hijacking | Low | High | Secure token storage, HTTPS only |

## Notes

- Local development uses Keycloak in Docker
- Production uses AWS Cognito
- Consider short token TTL (15 min) with refresh tokens (24 hours)
- Admin user disable should invalidate tokens immediately (or use short TTL)
