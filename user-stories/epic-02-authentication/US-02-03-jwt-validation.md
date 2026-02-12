# User Story: US-02-03 - JWT Token Validation and Claims Extraction

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-02-03 |
| **Epic** | Epic 02 - Authentication, Sessions & Identity |
| **Title** | JWT Token Validation and Claims Extraction |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |

## User Story

**As a** BFF service  
**I want** to validate JWT tokens and extract security claims  
**So that** I can authorize requests and enforce tenant/role-based access control

## Acceptance Criteria

### AC1: Token Validation
- [ ] Validate JWT signature using JWKS
- [ ] Validate token expiration
- [ ] Validate issuer claim
- [ ] Validate audience claim
- [ ] Cache JWKS keys with refresh

### AC2: Claims Extraction
- [ ] Extract standard claims (sub, email, name)
- [ ] Extract tenant_id claim
- [ ] Extract roles claim
- [ ] Extract entitlements claim
- [ ] Build SecurityContext from claims

### AC3: Middleware Integration
- [ ] Express middleware validates tokens
- [ ] Attaches SecurityContext to request
- [ ] Returns 401 for invalid tokens
- [ ] Returns 403 for missing permissions

### AC4: gRPC Context Propagation
- [ ] SecurityContext propagates to gRPC calls
- [ ] Internal services receive tenant context
- [ ] Correlation ID flows through

### AC5: Performance
- [ ] JWKS cached to avoid per-request fetch
- [ ] Token validation < 5ms per request
- [ ] Cache refresh on key rotation

## Technical Details

### JWT Validation Flow
```
Request → Extract Bearer Token → Validate Signature (JWKS)
                                       ↓
                              Validate Expiration
                                       ↓
                              Validate Issuer/Audience
                                       ↓
                              Extract Claims → Build SecurityContext
                                       ↓
                              Attach to Request → Next Handler
```

### Implementation

```typescript
// jwt/validator.ts
import { createRemoteJWKSet, jwtVerify, JWTPayload } from 'jose';
import { createLogger } from '@orion/observability';
import { SecurityContext, Role, Entitlements } from '../interfaces';

const logger = createLogger({ serviceName: 'jwt-validator' });

interface JWTValidatorConfig {
  jwksUri: string;
  issuer: string;
  audience: string;
  algorithms?: string[];
  clockTolerance?: number;
}

// Cache JWKS for performance
let jwksCache: ReturnType<typeof createRemoteJWKSet> | null = null;
let jwksUri: string | null = null;

function getJWKS(uri: string) {
  if (!jwksCache || jwksUri !== uri) {
    jwksCache = createRemoteJWKSet(new URL(uri), {
      cooldownDuration: 30000, // 30 seconds between refresh attempts
      cacheMaxAge: 600000,     // 10 minutes max cache
    });
    jwksUri = uri;
  }
  return jwksCache;
}

export interface OrionJWTPayload extends JWTPayload {
  email?: string;
  preferred_username?: string;
  name?: string;
  tenant_id: string;
  roles?: string[];
  entitlements?: {
    asset_classes?: string[];
    instruments?: string[];
    venues?: string[];
    max_notional?: number;
    rfq_rate_limit?: number;
    order_rate_limit?: number;
  };
}

export async function validateJWT(
  token: string,
  config: JWTValidatorConfig
): Promise<OrionJWTPayload> {
  const jwks = getJWKS(config.jwksUri);
  
  try {
    const { payload } = await jwtVerify(token, jwks, {
      issuer: config.issuer,
      audience: config.audience,
      algorithms: config.algorithms || ['RS256'],
      clockTolerance: config.clockTolerance || 30, // 30 seconds tolerance
    });
    
    // Validate required custom claims
    if (!payload.tenant_id) {
      throw new Error('Missing tenant_id claim');
    }
    
    return payload as OrionJWTPayload;
  } catch (error) {
    logger.warn('JWT validation failed', {
      error: (error as Error).message,
    });
    throw error;
  }
}

export function extractBearerToken(authHeader?: string): string | null {
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return null;
  }
  return authHeader.substring(7);
}

export function buildSecurityContext(
  payload: OrionJWTPayload,
  token: string,
  correlationId: string
): SecurityContext {
  return {
    user: {
      userId: payload.sub!,
      email: payload.email || '',
      username: payload.preferred_username || payload.email || '',
      displayName: payload.name,
    },
    tenant: {
      tenantId: payload.tenant_id,
    },
    roles: (payload.roles || []) as Role[],
    entitlements: {
      assetClasses: payload.entitlements?.asset_classes || [],
      instruments: payload.entitlements?.instruments || [],
      venues: payload.entitlements?.venues || [],
      limits: {
        maxNotional: payload.entitlements?.max_notional || 10000000,
        rfqRateLimit: payload.entitlements?.rfq_rate_limit || 10,
        orderRateLimit: payload.entitlements?.order_rate_limit || 100,
        maxOpenOrders: 100,
      },
    },
    token,
    correlationId,
  };
}
```

### Express Middleware

```typescript
// middleware/auth.middleware.ts
import { Request, Response, NextFunction } from 'express';
import { validateJWT, extractBearerToken, buildSecurityContext } from '../jwt/validator';
import { createLogger } from '@orion/observability';

const logger = createLogger({ serviceName: 'auth-middleware' });

export interface AuthMiddlewareConfig {
  jwksUri: string;
  issuer: string;
  audience: string;
  excludePaths?: string[];
}

export function authMiddleware(config: AuthMiddlewareConfig) {
  return async (req: Request, res: Response, next: NextFunction) => {
    // Skip auth for excluded paths
    if (config.excludePaths?.some(path => req.path.startsWith(path))) {
      return next();
    }
    
    const token = extractBearerToken(req.headers.authorization);
    
    if (!token) {
      logger.warn('Missing authorization token', { path: req.path });
      return res.status(401).json({
        error: 'unauthorized',
        message: 'Missing authorization token',
      });
    }
    
    try {
      const payload = await validateJWT(token, {
        jwksUri: config.jwksUri,
        issuer: config.issuer,
        audience: config.audience,
      });
      
      const correlationId = (req as any).correlationId || 'unknown';
      
      req.securityContext = buildSecurityContext(payload, token, correlationId);
      
      logger.debug('Request authenticated', {
        userId: req.securityContext.user.userId,
        tenantId: req.securityContext.tenant.tenantId,
        roles: req.securityContext.roles,
      });
      
      next();
    } catch (error) {
      logger.warn('Token validation failed', {
        path: req.path,
        error: (error as Error).message,
      });
      
      return res.status(401).json({
        error: 'unauthorized',
        message: 'Invalid or expired token',
      });
    }
  };
}
```

### gRPC Context Propagation

```typescript
// grpc/context-propagation.ts
import { Metadata } from '@grpc/grpc-js';
import { SecurityContext } from '../interfaces';

const CONTEXT_HEADER = 'x-security-context';

export function propagateContext(context: SecurityContext): Metadata {
  const metadata = new Metadata();
  
  metadata.set(CONTEXT_HEADER, Buffer.from(JSON.stringify({
    userId: context.user.userId,
    tenantId: context.tenant.tenantId,
    roles: context.roles,
    correlationId: context.correlationId,
  })).toString('base64'));
  
  metadata.set('authorization', `Bearer ${context.token}`);
  metadata.set('x-correlation-id', context.correlationId);
  metadata.set('x-tenant-id', context.tenant.tenantId);
  
  return metadata;
}

export function extractContextFromMetadata(metadata: Metadata): Partial<SecurityContext> {
  const encoded = metadata.get(CONTEXT_HEADER)[0] as string;
  if (!encoded) {
    throw new Error('Missing security context in metadata');
  }
  
  return JSON.parse(Buffer.from(encoded, 'base64').toString('utf-8'));
}
```

## Implementation Steps

1. Install `jose` library for JWT validation
2. Implement JWT validation with JWKS caching
3. Implement claims extraction
4. Create auth middleware
5. Implement gRPC context propagation
6. Add comprehensive tests
7. Benchmark performance

## Definition of Done

- [ ] JWT validation works with Keycloak
- [ ] All custom claims extracted
- [ ] Middleware blocks invalid requests
- [ ] gRPC propagation works
- [ ] Performance meets requirements
- [ ] Tests pass with >90% coverage

## Dependencies

- US-02-01: Local Keycloak
- US-01-04: Security Library

## Testing Requirements

### Unit Tests
- Valid token parsing
- Invalid signature rejection
- Expired token rejection
- Missing claims handling

### Integration Tests
- Full flow with Keycloak
- JWKS key rotation handling
- Performance benchmarks
