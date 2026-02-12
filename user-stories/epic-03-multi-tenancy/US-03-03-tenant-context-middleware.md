# User Story: US-03-03 - Tenant Context Middleware

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-03-03 |
| **Epic** | Epic 03 - Multi-Tenancy |
| **Title** | Tenant Context Middleware |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **PRD Reference** | FR-TENANT-03 |

## User Story

**As a** backend developer  
**I want** tenant context automatically extracted and propagated  
**So that** all service operations are properly scoped without manual handling

## Description

Implement middleware that extracts tenant context from JWT claims and propagates it through the entire request lifecycle. This context must be available in HTTP handlers, gRPC services, Kafka consumers, and any async operations.

## Acceptance Criteria

- [ ] Tenant ID extracted from JWT `tenant_id` claim
- [ ] Context available via `AsyncLocalStorage`
- [ ] Context propagated to database queries
- [ ] Context passed in Kafka message headers
- [ ] gRPC metadata carries tenant context
- [ ] Missing tenant context returns 403

## Technical Details

### Tenant Context Module

```typescript
// libs/security/src/tenant-context/tenant-context.ts
import { AsyncLocalStorage } from 'async_hooks';

export interface TenantContext {
  tenantId: string;
  tenantSlug?: string;
  tenantName?: string;
  userId?: string;
  userEmail?: string;
  roles: string[];
  permissions: string[];
  correlationId: string;
  isAdmin: boolean;
}

export const tenantContextStorage = new AsyncLocalStorage<TenantContext>();

export function getCurrentTenant(): TenantContext | undefined {
  return tenantContextStorage.getStore();
}

export function requireTenant(): TenantContext {
  const context = getCurrentTenant();
  if (!context) {
    throw new TenantContextError('Tenant context not available');
  }
  return context;
}

export function getTenantId(): string {
  return requireTenant().tenantId;
}

export function runWithTenant<T>(context: TenantContext, fn: () => T): T {
  return tenantContextStorage.run(context, fn);
}

export class TenantContextError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TenantContextError';
  }
}
```

### Express Middleware

```typescript
// libs/security/src/tenant-context/express-middleware.ts
import { Request, Response, NextFunction } from 'express';
import { tenantContextStorage, TenantContext } from './tenant-context';
import { JWTPayload } from '../jwt/types';
import { logger } from '@orion/observability';

export interface TenantMiddlewareOptions {
  requireTenant?: boolean;
  adminBypass?: boolean;
  tenantHeader?: string;
}

export function tenantContextMiddleware(options: TenantMiddlewareOptions = {}) {
  const {
    requireTenant = true,
    adminBypass = true,
    tenantHeader = 'X-Tenant-ID',
  } = options;

  return (req: Request, res: Response, next: NextFunction) => {
    const user = req.user as JWTPayload | undefined;
    
    // Extract tenant from JWT claims
    let tenantId = user?.tenant_id;
    
    // Admin can override via header (for cross-tenant operations)
    if (adminBypass && user?.roles?.includes('platform_admin')) {
      const headerTenant = req.headers[tenantHeader.toLowerCase()] as string;
      if (headerTenant) {
        tenantId = headerTenant;
        logger.info('Admin tenant override', { 
          adminUserId: user.sub, 
          targetTenant: tenantId 
        });
      }
    }

    // Validate tenant presence
    if (requireTenant && !tenantId) {
      logger.warn('Missing tenant context', { 
        path: req.path, 
        userId: user?.sub 
      });
      return res.status(403).json({
        error: 'Forbidden',
        message: 'Tenant context required',
        code: 'TENANT_REQUIRED',
      });
    }

    // Build tenant context
    const context: TenantContext = {
      tenantId: tenantId || '',
      userId: user?.sub,
      userEmail: user?.email,
      roles: user?.roles || [],
      permissions: user?.permissions || [],
      correlationId: (req.headers['x-correlation-id'] as string) || crypto.randomUUID(),
      isAdmin: user?.roles?.includes('platform_admin') || false,
    };

    // Run request handler within tenant context
    tenantContextStorage.run(context, () => {
      // Add context to request for convenience
      req.tenantContext = context;
      
      // Set correlation ID header for downstream services
      res.setHeader('X-Correlation-ID', context.correlationId);
      
      next();
    });
  };
}

// Type augmentation for Express Request
declare global {
  namespace Express {
    interface Request {
      tenantContext?: TenantContext;
    }
  }
}
```

### gRPC Interceptor

```typescript
// libs/security/src/tenant-context/grpc-interceptor.ts
import { Metadata, ServerUnaryCall, ServerWritableStream } from '@grpc/grpc-js';
import { tenantContextStorage, TenantContext } from './tenant-context';
import { logger } from '@orion/observability';

const TENANT_ID_KEY = 'x-tenant-id';
const USER_ID_KEY = 'x-user-id';
const CORRELATION_ID_KEY = 'x-correlation-id';
const ROLES_KEY = 'x-user-roles';

export function extractTenantFromMetadata(metadata: Metadata): TenantContext | null {
  const tenantId = metadata.get(TENANT_ID_KEY)[0]?.toString();
  
  if (!tenantId) {
    return null;
  }

  return {
    tenantId,
    userId: metadata.get(USER_ID_KEY)[0]?.toString(),
    correlationId: metadata.get(CORRELATION_ID_KEY)[0]?.toString() || crypto.randomUUID(),
    roles: metadata.get(ROLES_KEY)[0]?.toString().split(',') || [],
    permissions: [],
    isAdmin: false,
  };
}

export function injectTenantToMetadata(context: TenantContext): Metadata {
  const metadata = new Metadata();
  metadata.set(TENANT_ID_KEY, context.tenantId);
  if (context.userId) {
    metadata.set(USER_ID_KEY, context.userId);
  }
  metadata.set(CORRELATION_ID_KEY, context.correlationId);
  if (context.roles.length > 0) {
    metadata.set(ROLES_KEY, context.roles.join(','));
  }
  return metadata;
}

export function tenantInterceptor() {
  return async (
    call: ServerUnaryCall<any, any> | ServerWritableStream<any, any>,
    methodName: string,
    callback: (error: Error | null, value?: any) => void,
    handler: () => Promise<any>
  ) => {
    const context = extractTenantFromMetadata(call.metadata);
    
    if (!context) {
      logger.warn('Missing tenant in gRPC metadata', { method: methodName });
      callback({
        name: 'PERMISSION_DENIED',
        message: 'Tenant context required',
        code: 7, // PERMISSION_DENIED
      } as Error);
      return;
    }

    try {
      const result = await tenantContextStorage.run(context, handler);
      callback(null, result);
    } catch (error) {
      callback(error as Error);
    }
  };
}
```

### Kafka Consumer Context

```typescript
// libs/security/src/tenant-context/kafka-context.ts
import { EachMessagePayload, KafkaMessage } from 'kafkajs';
import { tenantContextStorage, TenantContext } from './tenant-context';
import { logger } from '@orion/observability';

const TENANT_HEADER = 'x-tenant-id';
const USER_HEADER = 'x-user-id';
const CORRELATION_HEADER = 'x-correlation-id';

export function extractTenantFromKafkaHeaders(message: KafkaMessage): TenantContext | null {
  const headers = message.headers || {};
  
  const tenantId = headers[TENANT_HEADER]?.toString();
  if (!tenantId) {
    return null;
  }

  return {
    tenantId,
    userId: headers[USER_HEADER]?.toString(),
    correlationId: headers[CORRELATION_HEADER]?.toString() || crypto.randomUUID(),
    roles: [],
    permissions: [],
    isAdmin: false,
  };
}

export function createTenantHeaders(context: TenantContext): Record<string, string> {
  return {
    [TENANT_HEADER]: context.tenantId,
    [USER_HEADER]: context.userId || '',
    [CORRELATION_HEADER]: context.correlationId,
  };
}

export function withTenantContext<T>(
  payload: EachMessagePayload,
  handler: (context: TenantContext) => Promise<T>
): Promise<T> {
  const context = extractTenantFromKafkaHeaders(payload.message);
  
  if (!context) {
    logger.error('Missing tenant in Kafka message', {
      topic: payload.topic,
      partition: payload.partition,
      offset: payload.message.offset,
    });
    throw new Error('Tenant context required in Kafka message');
  }

  return tenantContextStorage.run(context, () => handler(context));
}

// Kafka producer helper
export async function publishWithTenant(
  producer: any,
  topic: string,
  message: { key?: string; value: any },
) {
  const context = tenantContextStorage.getStore();
  
  if (!context) {
    throw new Error('Cannot publish without tenant context');
  }

  await producer.send({
    topic,
    messages: [{
      key: message.key,
      value: JSON.stringify(message.value),
      headers: createTenantHeaders(context),
    }],
  });
}
```

### NestJS Module

```typescript
// libs/security/src/tenant-context/tenant-context.module.ts
import { Module, Global, DynamicModule } from '@nestjs/common';
import { APP_INTERCEPTOR, APP_GUARD } from '@nestjs/core';
import { TenantContextInterceptor } from './tenant-context.interceptor';
import { TenantGuard } from './tenant.guard';

export interface TenantModuleOptions {
  requireTenant?: boolean;
  adminBypass?: boolean;
  excludePaths?: string[];
}

@Global()
@Module({})
export class TenantContextModule {
  static forRoot(options: TenantModuleOptions = {}): DynamicModule {
    return {
      module: TenantContextModule,
      providers: [
        {
          provide: 'TENANT_OPTIONS',
          useValue: options,
        },
        {
          provide: APP_INTERCEPTOR,
          useClass: TenantContextInterceptor,
        },
        {
          provide: APP_GUARD,
          useClass: TenantGuard,
        },
      ],
      exports: ['TENANT_OPTIONS'],
    };
  }
}
```

```typescript
// libs/security/src/tenant-context/tenant-context.interceptor.ts
import {
  Injectable,
  NestInterceptor,
  ExecutionContext,
  CallHandler,
} from '@nestjs/common';
import { Observable } from 'rxjs';
import { tenantContextStorage, TenantContext } from './tenant-context';

@Injectable()
export class TenantContextInterceptor implements NestInterceptor {
  intercept(executionContext: ExecutionContext, next: CallHandler): Observable<any> {
    const request = executionContext.switchToHttp().getRequest();
    const user = request.user;

    if (!user?.tenant_id) {
      return next.handle();
    }

    const context: TenantContext = {
      tenantId: user.tenant_id,
      userId: user.sub,
      userEmail: user.email,
      roles: user.roles || [],
      permissions: user.permissions || [],
      correlationId: request.headers['x-correlation-id'] || crypto.randomUUID(),
      isAdmin: user.roles?.includes('platform_admin') || false,
    };

    return new Observable((subscriber) => {
      tenantContextStorage.run(context, () => {
        next.handle().subscribe({
          next: (value) => subscriber.next(value),
          error: (err) => subscriber.error(err),
          complete: () => subscriber.complete(),
        });
      });
    });
  }
}
```

### Decorator for Easy Access

```typescript
// libs/security/src/tenant-context/decorators.ts
import { createParamDecorator, ExecutionContext } from '@nestjs/common';
import { getCurrentTenant, TenantContext } from './tenant-context';

export const CurrentTenant = createParamDecorator(
  (data: keyof TenantContext | undefined, ctx: ExecutionContext) => {
    const context = getCurrentTenant();
    
    if (!context) {
      return undefined;
    }

    return data ? context[data] : context;
  },
);

// Usage in controller:
// @Get()
// async getItems(@CurrentTenant('tenantId') tenantId: string) { ... }
// 
// @Get()
// async getItems(@CurrentTenant() context: TenantContext) { ... }
```

## Implementation Steps

1. **Create tenant context module**
   - Implement AsyncLocalStorage wrapper
   - Add helper functions
   - Export types

2. **Build Express middleware**
   - Extract from JWT
   - Validate presence
   - Set up context storage

3. **Create gRPC interceptor**
   - Extract from metadata
   - Inject to outgoing calls
   - Handle errors

4. **Build Kafka helpers**
   - Extract from headers
   - Inject to published messages
   - Wrap consumers

5. **Create NestJS module**
   - Global interceptor
   - Guard for validation
   - Decorators for access

6. **Integration testing**
   - Test all transport layers
   - Verify propagation
   - Test error cases

## Definition of Done

- [ ] Express middleware extracts tenant
- [ ] gRPC interceptor propagates context
- [ ] Kafka messages carry tenant headers
- [ ] AsyncLocalStorage works across async
- [ ] Decorators provide easy access
- [ ] Unit tests cover all cases
- [ ] Integration tests verify propagation

## Dependencies

- **US-02-03**: JWT Token Validation
- **US-01-04**: Shared Security Library

## Test Cases

```typescript
describe('TenantContextMiddleware', () => {
  describe('Express', () => {
    it('should extract tenant from JWT', async () => {
      const response = await request(app)
        .get('/api/test')
        .set('Authorization', `Bearer ${tokenWithTenant}`);
      
      expect(response.status).toBe(200);
      expect(response.body.tenantId).toBe('test-tenant-id');
    });

    it('should return 403 when tenant missing', async () => {
      const response = await request(app)
        .get('/api/test')
        .set('Authorization', `Bearer ${tokenWithoutTenant}`);
      
      expect(response.status).toBe(403);
      expect(response.body.code).toBe('TENANT_REQUIRED');
    });

    it('should allow admin tenant override', async () => {
      const response = await request(app)
        .get('/api/test')
        .set('Authorization', `Bearer ${adminToken}`)
        .set('X-Tenant-ID', 'target-tenant');
      
      expect(response.body.tenantId).toBe('target-tenant');
    });
  });

  describe('Context Propagation', () => {
    it('should be available in async callbacks', async () => {
      let capturedTenant: string | undefined;
      
      await tenantContextStorage.run({ tenantId: 'test' }, async () => {
        await new Promise(resolve => setTimeout(resolve, 10));
        capturedTenant = getCurrentTenant()?.tenantId;
      });
      
      expect(capturedTenant).toBe('test');
    });

    it('should propagate through Promise chains', async () => {
      const results: string[] = [];
      
      await tenantContextStorage.run({ tenantId: 'test' }, async () => {
        await Promise.resolve()
          .then(() => results.push(getCurrentTenant()?.tenantId || 'none'))
          .then(() => results.push(getCurrentTenant()?.tenantId || 'none'));
      });
      
      expect(results).toEqual(['test', 'test']);
    });
  });
});
```

## Notes

- AsyncLocalStorage is stable in Node.js 16+
- Context is lost across worker threads - use explicit passing
- Performance overhead is minimal (~1-2%)
- Consider context cloning for parallel operations
