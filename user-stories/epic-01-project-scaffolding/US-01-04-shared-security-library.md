# User Story: US-01-04 - Setup Shared Security Library

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-04 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Setup Shared Security Library |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **Type** | Technical Foundation |

## User Story

**As a** service developer  
**I want** a shared security library with authentication helpers, tenant enforcement, and authorization utilities  
**So that** all services implement consistent security patterns without code duplication

## Description

This story creates the `@orion/security` library that provides authentication token validation, tenant context extraction, role-based access control (RBAC) helpers, attribute-based access control (ABAC) utilities, and middleware for Express.js services. This library ensures consistent security implementation across all Orion services.

## Acceptance Criteria

### AC1: JWT Token Handling
- [ ] `validateJwt(token, options): JwtPayload` validates JWT tokens
- [ ] `extractBearerToken(authHeader): string` extracts token from Authorization header
- [ ] Support for JWKS-based key validation (for Cognito/Keycloak)
- [ ] Configurable issuer and audience validation
- [ ] Token expiration checking

### AC2: Tenant Context
- [ ] `TenantContext` interface defining tenant-scoped request context
- [ ] `extractTenantContext(jwt): TenantContext` extracts tenant info from JWT
- [ ] `enforceTenantIsolation(context, resourceTenantId)` throws if mismatch
- [ ] Middleware to attach tenant context to request

### AC3: Role-Based Access Control (RBAC)
- [ ] `Roles` enum/const with all platform roles from PRD
- [ ] `hasRole(context, role): boolean` checks if user has role
- [ ] `requireRole(role)` middleware factory for Express
- [ ] `requireAnyRole(roles[])` middleware factory
- [ ] `requireAllRoles(roles[])` middleware factory

### AC4: Attribute-Based Access Control (ABAC)
- [ ] `Entitlements` interface defining user entitlements
- [ ] `checkEntitlement(context, resource, action): boolean`
- [ ] Support for asset class restrictions
- [ ] Support for instrument restrictions
- [ ] Support for venue restrictions

### AC5: Express Middleware
- [ ] `authMiddleware(options)` validates JWT and attaches context
- [ ] `tenantMiddleware()` enforces tenant from JWT
- [ ] `roleMiddleware(roles)` checks user roles
- [ ] `rateLimitMiddleware(config)` basic rate limiting per user/tenant

### AC6: gRPC Interceptors
- [ ] `authInterceptor` for gRPC server authentication
- [ ] `contextInterceptor` to extract and propagate security context
- [ ] `tenantInterceptor` for tenant enforcement in gRPC calls

### AC7: Security Context Propagation
- [ ] `SecurityContext` interface for passing auth info between services
- [ ] `serializeContext(context): string` for gRPC metadata
- [ ] `deserializeContext(metadata): SecurityContext`

### AC8: Testing
- [ ] Unit tests for all functions
- [ ] Mock JWT generator for testing
- [ ] Test utilities for creating test contexts
- [ ] Test coverage > 85%

## Technical Details

### Directory Structure

```
/libs/security/
├── src/
│   ├── index.ts                    # Public exports
│   ├── interfaces/
│   │   ├── jwt-payload.ts          # JWT payload interface
│   │   ├── tenant-context.ts       # Tenant context interface
│   │   ├── security-context.ts     # Full security context
│   │   └── entitlements.ts         # Entitlements interface
│   ├── constants/
│   │   ├── roles.ts                # Role definitions
│   │   └── permissions.ts          # Permission definitions
│   ├── jwt/
│   │   ├── validator.ts            # JWT validation
│   │   ├── extractor.ts            # Token extraction
│   │   └── jwks-client.ts          # JWKS key fetching
│   ├── rbac/
│   │   ├── role-checker.ts         # Role checking functions
│   │   └── permission-checker.ts   # Permission checking
│   ├── abac/
│   │   ├── entitlement-checker.ts  # Entitlement validation
│   │   └── policy-engine.ts        # ABAC policy engine
│   ├── middleware/
│   │   ├── express/
│   │   │   ├── auth.middleware.ts
│   │   │   ├── tenant.middleware.ts
│   │   │   └── role.middleware.ts
│   │   └── grpc/
│   │       ├── auth.interceptor.ts
│   │       └── context.interceptor.ts
│   ├── tenant/
│   │   ├── context-extractor.ts
│   │   └── isolation-enforcer.ts
│   └── testing/
│       ├── mock-jwt.ts             # Test JWT generator
│       └── test-context.ts         # Test context factories
├── __tests__/
│   ├── jwt/
│   ├── rbac/
│   ├── middleware/
│   └── tenant/
├── project.json
├── tsconfig.json
└── README.md
```

### Key Interfaces

#### Security Context (`interfaces/security-context.ts`)
```typescript
export interface SecurityContext {
  /** Authenticated user information */
  user: AuthenticatedUser;
  
  /** Tenant context for isolation */
  tenant: TenantContext;
  
  /** User's roles */
  roles: Role[];
  
  /** User's entitlements for ABAC */
  entitlements: Entitlements;
  
  /** Original JWT token (for forwarding) */
  token: string;
  
  /** Correlation ID for tracing */
  correlationId: string;
}

export interface AuthenticatedUser {
  userId: string;
  email: string;
  username: string;
  displayName?: string;
}

export interface TenantContext {
  tenantId: string;
  tenantName?: string;
  tenantType?: 'standard' | 'premium' | 'enterprise';
}
```

#### Entitlements (`interfaces/entitlements.ts`)
```typescript
export interface Entitlements {
  /** Allowed asset classes */
  assetClasses: AssetClass[];
  
  /** Allowed instrument IDs (empty = all allowed) */
  instruments: string[];
  
  /** Allowed venue IDs (empty = all allowed) */
  venues: string[];
  
  /** Trading limits */
  limits: TradingLimits;
}

export interface TradingLimits {
  /** Maximum notional per trade */
  maxNotional: number;
  
  /** Maximum RFQs per minute */
  rfqRateLimit: number;
  
  /** Maximum orders per minute */
  orderRateLimit: number;
  
  /** Maximum open orders */
  maxOpenOrders: number;
}

export enum AssetClass {
  FX = 'FX',
  RATES = 'RATES',
  CREDIT = 'CREDIT',
  EQUITIES = 'EQUITIES',
  COMMODITIES = 'COMMODITIES',
}
```

#### Roles (`constants/roles.ts`)
```typescript
/**
 * Platform roles as defined in PRD Section 5.2
 */
export const Roles = {
  TRADER: 'ROLE_TRADER',
  SALES: 'ROLE_SALES',
  RISK: 'ROLE_RISK',
  ANALYST: 'ROLE_ANALYST',
  ADMIN: 'ROLE_ADMIN',
  PLATFORM: 'ROLE_PLATFORM',
} as const;

export type Role = typeof Roles[keyof typeof Roles];

/**
 * Role hierarchy for permission inheritance
 */
export const RoleHierarchy: Record<Role, Role[]> = {
  [Roles.ADMIN]: [Roles.TRADER, Roles.SALES, Roles.RISK, Roles.ANALYST],
  [Roles.SALES]: [Roles.TRADER],
  [Roles.TRADER]: [],
  [Roles.RISK]: [],
  [Roles.ANALYST]: [],
  [Roles.PLATFORM]: [],
};
```

### Middleware Implementation

#### Express Auth Middleware
```typescript
import { Request, Response, NextFunction } from 'express';
import { validateJwt } from '../jwt/validator';
import { extractBearerToken } from '../jwt/extractor';
import { SecurityContext } from '../interfaces/security-context';

export interface AuthMiddlewareOptions {
  jwksUri: string;
  issuer: string;
  audience: string;
  algorithms?: string[];
}

declare global {
  namespace Express {
    interface Request {
      securityContext?: SecurityContext;
    }
  }
}

export function authMiddleware(options: AuthMiddlewareOptions) {
  return async (req: Request, res: Response, next: NextFunction) => {
    try {
      const token = extractBearerToken(req.headers.authorization);
      
      if (!token) {
        return res.status(401).json({ error: 'Missing authorization token' });
      }
      
      const payload = await validateJwt(token, options);
      
      req.securityContext = {
        user: {
          userId: payload.sub,
          email: payload.email,
          username: payload.preferred_username || payload.email,
        },
        tenant: {
          tenantId: payload.tenant_id,
          tenantName: payload.tenant_name,
        },
        roles: payload.roles || [],
        entitlements: payload.entitlements || getDefaultEntitlements(),
        token,
        correlationId: req.headers['x-correlation-id'] as string || createCorrelationId(),
      };
      
      next();
    } catch (error) {
      return res.status(401).json({ error: 'Invalid or expired token' });
    }
  };
}
```

#### Role Middleware
```typescript
import { Request, Response, NextFunction } from 'express';
import { Role } from '../constants/roles';

export function requireRole(role: Role) {
  return (req: Request, res: Response, next: NextFunction) => {
    const context = req.securityContext;
    
    if (!context) {
      return res.status(401).json({ error: 'Not authenticated' });
    }
    
    if (!context.roles.includes(role)) {
      return res.status(403).json({ 
        error: 'Insufficient permissions',
        required: role,
        actual: context.roles,
      });
    }
    
    next();
  };
}

export function requireAnyRole(roles: Role[]) {
  return (req: Request, res: Response, next: NextFunction) => {
    const context = req.securityContext;
    
    if (!context) {
      return res.status(401).json({ error: 'Not authenticated' });
    }
    
    const hasAnyRole = roles.some(role => context.roles.includes(role));
    
    if (!hasAnyRole) {
      return res.status(403).json({ 
        error: 'Insufficient permissions',
        required: `any of [${roles.join(', ')}]`,
        actual: context.roles,
      });
    }
    
    next();
  };
}
```

### gRPC Interceptors

```typescript
import { ServerUnaryCall, Metadata, status } from '@grpc/grpc-js';
import { SecurityContext } from '../interfaces/security-context';

const SECURITY_CONTEXT_KEY = 'x-security-context';

/**
 * Server interceptor to validate authentication on gRPC calls.
 */
export function createAuthInterceptor(options: AuthInterceptorOptions) {
  return async (
    call: ServerUnaryCall<any, any>,
    callback: (error: any, response?: any) => void,
    next: () => Promise<any>
  ) => {
    try {
      const metadata = call.metadata;
      const token = metadata.get('authorization')[0] as string;
      
      if (!token) {
        return callback({
          code: status.UNAUTHENTICATED,
          message: 'Missing authorization token',
        });
      }
      
      const securityContext = await validateAndBuildContext(token, options);
      
      // Attach context to call for downstream use
      (call as any).securityContext = securityContext;
      
      return next();
    } catch (error) {
      return callback({
        code: status.UNAUTHENTICATED,
        message: 'Invalid or expired token',
      });
    }
  };
}

/**
 * Serialize security context for gRPC metadata propagation.
 */
export function serializeSecurityContext(context: SecurityContext): string {
  return Buffer.from(JSON.stringify({
    userId: context.user.userId,
    tenantId: context.tenant.tenantId,
    roles: context.roles,
    correlationId: context.correlationId,
  })).toString('base64');
}

/**
 * Deserialize security context from gRPC metadata.
 */
export function deserializeSecurityContext(encoded: string): Partial<SecurityContext> {
  return JSON.parse(Buffer.from(encoded, 'base64').toString('utf-8'));
}
```

### Testing Utilities

```typescript
import jwt from 'jsonwebtoken';
import { SecurityContext, Role, Entitlements } from '../';

const TEST_SECRET = 'test-secret-key';

export interface MockJwtOptions {
  userId?: string;
  email?: string;
  tenantId?: string;
  roles?: Role[];
  entitlements?: Partial<Entitlements>;
  expiresIn?: string;
}

/**
 * Generate a mock JWT for testing purposes.
 */
export function createMockJwt(options: MockJwtOptions = {}): string {
  const payload = {
    sub: options.userId || 'test-user-001',
    email: options.email || 'test@orion.local',
    tenant_id: options.tenantId || 'test-tenant-001',
    roles: options.roles || [Roles.TRADER],
    entitlements: options.entitlements || getDefaultEntitlements(),
  };
  
  return jwt.sign(payload, TEST_SECRET, {
    expiresIn: options.expiresIn || '1h',
    issuer: 'test-issuer',
    audience: 'test-audience',
  });
}

/**
 * Create a mock security context for testing.
 */
export function createMockSecurityContext(
  overrides: Partial<SecurityContext> = {}
): SecurityContext {
  return {
    user: {
      userId: 'test-user-001',
      email: 'test@orion.local',
      username: 'testuser',
      ...overrides.user,
    },
    tenant: {
      tenantId: 'test-tenant-001',
      tenantName: 'Test Tenant',
      ...overrides.tenant,
    },
    roles: overrides.roles || [Roles.TRADER],
    entitlements: overrides.entitlements || getDefaultEntitlements(),
    token: 'mock-token',
    correlationId: overrides.correlationId || 'test-correlation-001',
  };
}
```

## Implementation Steps

1. **Create Library Scaffold**
   ```bash
   nx generate @nx/js:library security --directory=libs --bundler=esbuild
   ```

2. **Define Interfaces**
   - Create all security-related interfaces
   - Document with JSDoc

3. **Implement JWT Handling**
   - Create token validator
   - Implement JWKS client
   - Add token extraction utilities

4. **Implement RBAC**
   - Define roles and permissions
   - Create role checking functions

5. **Implement ABAC**
   - Create entitlement checker
   - Build policy engine

6. **Create Middleware**
   - Express middleware for HTTP
   - gRPC interceptors for internal calls

7. **Create Test Utilities**
   - Mock JWT generator
   - Test context factories

8. **Write Tests**
   - Unit tests for all functions
   - Integration tests for middleware

## Definition of Done

- [ ] All acceptance criteria met
- [ ] Library builds without errors
- [ ] All tests pass with > 85% coverage
- [ ] Documentation complete
- [ ] Can be imported as `@orion/security`
- [ ] Code review approved

## Dependencies

- US-01-01: Initialize Monorepo

## Notes

- JWT validation will use JWKS in production (Cognito/Keycloak)
- Test utilities use symmetric keys for simplicity
- Security context must propagate through all service calls
- Rate limiting is basic; production may use Redis-backed implementation
