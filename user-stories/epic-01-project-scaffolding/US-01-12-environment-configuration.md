# User Story: US-01-12 - Setup Environment Configuration Management

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-12 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Setup Environment Configuration Management |
| **Priority** | P0 - Critical |
| **Story Points** | 3 |
| **Type** | Technical Foundation |

## User Story

**As a** developer and operator  
**I want** a consistent configuration management approach across all environments  
**So that** services can be configured safely without secrets in code

## Acceptance Criteria

### AC1: Environment Files
- [ ] `.env.example` files for all services
- [ ] Environment-specific config patterns
- [ ] All `.env` files in `.gitignore`

### AC2: Configuration Schema
- [ ] Zod schemas for config validation
- [ ] Required vs optional fields clear
- [ ] Default values documented

### AC3: Secrets Management Pattern
- [ ] Pattern for AWS Secrets Manager integration
- [ ] Local development secrets approach
- [ ] Secret rotation support

### AC4: Multi-Environment Support
- [ ] Local development configuration
- [ ] Dev environment configuration
- [ ] Staging environment configuration
- [ ] Production configuration patterns

### AC5: Configuration Validation
- [ ] Services fail fast on invalid config
- [ ] Clear error messages for missing config
- [ ] Config validation in CI

## Technical Details

### Environment Configuration Pattern
```typescript
// config/schema.ts
import { z } from 'zod';

const envSchema = z.object({
  NODE_ENV: z.enum(['development', 'staging', 'production']).default('development'),
  
  // Service identification
  SERVICE_NAME: z.string(),
  SERVICE_VERSION: z.string().default('1.0.0'),
  
  // HTTP Server
  HTTP_PORT: z.string().transform(Number).default('3000'),
  HTTP_HOST: z.string().default('0.0.0.0'),
  
  // gRPC Server
  GRPC_PORT: z.string().transform(Number).default('50051'),
  
  // Database
  DATABASE_URL: z.string().url(),
  DB_POOL_SIZE: z.string().transform(Number).default('10'),
  
  // Kafka
  KAFKA_BROKERS: z.string().transform(s => s.split(',')),
  KAFKA_CLIENT_ID: z.string(),
  KAFKA_GROUP_ID: z.string(),
  
  // Redis
  REDIS_URL: z.string().url().optional(),
  
  // Auth
  AUTH_JWKS_URI: z.string().url(),
  AUTH_ISSUER: z.string(),
  AUTH_AUDIENCE: z.string(),
  
  // Observability
  TRACING_ENABLED: z.string().transform(s => s === 'true').default('false'),
  OTLP_ENDPOINT: z.string().url().optional(),
  LOG_LEVEL: z.enum(['error', 'warn', 'info', 'debug', 'trace']).default('info'),
});

export type Env = z.infer<typeof envSchema>;

export function validateEnv(): Env {
  const result = envSchema.safeParse(process.env);
  
  if (!result.success) {
    console.error('❌ Invalid environment variables:');
    result.error.issues.forEach(issue => {
      console.error(`  - ${issue.path.join('.')}: ${issue.message}`);
    });
    process.exit(1);
  }
  
  return result.data;
}
```

### Example .env.example
```bash
# Service Configuration
SERVICE_NAME=bff-workstation
SERVICE_VERSION=1.0.0
NODE_ENV=development

# HTTP Server
HTTP_PORT=3000
HTTP_HOST=0.0.0.0

# gRPC Server
GRPC_PORT=50051

# Database
DATABASE_URL=postgres://orion:orion_dev_password@localhost:5432/orion
DB_POOL_SIZE=10

# Kafka
KAFKA_BROKERS=localhost:19092
KAFKA_CLIENT_ID=bff-workstation
KAFKA_GROUP_ID=bff-workstation-group

# Redis
REDIS_URL=redis://localhost:6379

# Authentication
AUTH_JWKS_URI=http://localhost:8180/realms/orion/protocol/openid-connect/certs
AUTH_ISSUER=http://localhost:8180/realms/orion
AUTH_AUDIENCE=orion-api

# Observability
TRACING_ENABLED=false
OTLP_ENDPOINT=http://localhost:4318
LOG_LEVEL=debug
```

## Implementation Steps

1. Create configuration schema library
2. Create `.env.example` templates
3. Document configuration variables
4. Add config validation to service template
5. Test validation error messages

## Definition of Done

- [ ] All acceptance criteria met
- [ ] Services validate config at startup
- [ ] Clear error messages for missing config
- [ ] Documentation complete

## Dependencies

- US-01-01: Initialize Monorepo
- US-01-09: Base Service Template
