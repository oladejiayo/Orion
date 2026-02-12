# User Story: US-02-01 - Setup Local Keycloak for Development

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-02-01 |
| **Epic** | Epic 02 - Authentication, Sessions & Identity |
| **Title** | Setup Local Keycloak for Development |
| **Priority** | P0 - Critical |
| **Story Points** | 3 |

## User Story

**As a** developer  
**I want** a local Keycloak instance pre-configured for Orion  
**So that** I can develop and test authentication flows without cloud dependencies

## Acceptance Criteria

### AC1: Keycloak Container
- [ ] Keycloak added to docker-compose
- [ ] Admin console accessible at http://localhost:8180
- [ ] Default admin credentials documented
- [ ] Health check configured

### AC2: Orion Realm Configuration
- [ ] "orion" realm pre-created
- [ ] Client for workstation UI configured
- [ ] Client for admin UI configured
- [ ] JWT token settings configured

### AC3: Pre-configured Users
- [ ] Test trader user with ROLE_TRADER
- [ ] Test admin user with ROLE_ADMIN
- [ ] Test analyst user with ROLE_ANALYST
- [ ] All users in test tenant

### AC4: Custom Claims
- [ ] tenant_id claim mapper configured
- [ ] roles claim mapper configured
- [ ] entitlements claim mapper configured

### AC5: Realm Export
- [ ] Realm configuration exported to JSON
- [ ] Auto-import on container start
- [ ] Easy reset to default state

## Technical Details

### Docker Compose Addition
```yaml
keycloak:
  image: quay.io/keycloak/keycloak:23.0
  container_name: orion-keycloak
  command: start-dev --import-realm
  environment:
    KEYCLOAK_ADMIN: admin
    KEYCLOAK_ADMIN_PASSWORD: admin
    KC_HTTP_PORT: 8180
  ports:
    - "8180:8180"
  volumes:
    - ./keycloak/realm-export.json:/opt/keycloak/data/import/orion-realm.json
  networks:
    - orion-network
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8180/health/ready"]
    interval: 10s
    timeout: 5s
    retries: 5
```

### Realm Configuration (`keycloak/realm-export.json`)
```json
{
  "realm": "orion",
  "enabled": true,
  "sslRequired": "none",
  "registrationAllowed": false,
  "loginWithEmailAllowed": true,
  "duplicateEmailsAllowed": false,
  "resetPasswordAllowed": true,
  "editUsernameAllowed": false,
  "bruteForceProtected": true,
  "accessTokenLifespan": 900,
  "refreshTokenLifespan": 86400,
  "clients": [
    {
      "clientId": "orion-workstation",
      "enabled": true,
      "publicClient": true,
      "redirectUris": ["http://localhost:3000/*"],
      "webOrigins": ["http://localhost:3000"],
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "protocolMappers": [
        {
          "name": "tenant_id",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-usermodel-attribute-mapper",
          "config": {
            "claim.name": "tenant_id",
            "user.attribute": "tenant_id",
            "id.token.claim": "true",
            "access.token.claim": "true"
          }
        }
      ]
    }
  ],
  "users": [
    {
      "username": "trader@test.local",
      "email": "trader@test.local",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Test",
      "lastName": "Trader",
      "credentials": [{"type": "password", "value": "password", "temporary": false}],
      "attributes": {"tenant_id": ["test-tenant-001"]},
      "realmRoles": ["ROLE_TRADER"]
    },
    {
      "username": "admin@test.local",
      "email": "admin@test.local",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Test",
      "lastName": "Admin",
      "credentials": [{"type": "password", "value": "password", "temporary": false}],
      "attributes": {"tenant_id": ["test-tenant-001"]},
      "realmRoles": ["ROLE_ADMIN", "ROLE_TRADER"]
    }
  ],
  "roles": {
    "realm": [
      {"name": "ROLE_TRADER"},
      {"name": "ROLE_SALES"},
      {"name": "ROLE_RISK"},
      {"name": "ROLE_ANALYST"},
      {"name": "ROLE_ADMIN"},
      {"name": "ROLE_PLATFORM"}
    ]
  }
}
```

### Test Users

| Username | Password | Roles | Tenant |
|----------|----------|-------|--------|
| trader@test.local | password | ROLE_TRADER | test-tenant-001 |
| admin@test.local | password | ROLE_ADMIN, ROLE_TRADER | test-tenant-001 |
| analyst@test.local | password | ROLE_ANALYST | test-tenant-001 |
| risk@test.local | password | ROLE_RISK | test-tenant-001 |

## Implementation Steps

1. Create `/infra/docker-compose/keycloak/` directory
2. Create realm export JSON with all configuration
3. Add Keycloak to docker-compose.yml
4. Test realm import on container start
5. Verify token endpoint works
6. Document access URLs and credentials

## Definition of Done

- [ ] Keycloak starts with docker-compose
- [ ] Admin console accessible
- [ ] Test users can login
- [ ] Tokens contain custom claims
- [ ] Documentation complete

## Dependencies

- US-01-02: Docker Compose Local Environment
