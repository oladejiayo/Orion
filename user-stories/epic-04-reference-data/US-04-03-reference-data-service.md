# User Story: US-04-03 - Reference Data Service

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-04-03 |
| **Epic** | Epic 04 - Reference Data Management |
| **Title** | Reference Data Service |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |

## User Story

**As a** platform developer  
**I want** a centralized reference data service with gRPC API  
**So that** all services can efficiently look up instruments and counterparties

## Description

Build the reference data microservice that serves as the single source of truth for instruments and counterparties. It exposes both REST (for admin) and gRPC (for internal services) APIs.

## Acceptance Criteria

- [ ] NestJS microservice structure
- [ ] gRPC server for internal lookups
- [ ] REST API for admin operations
- [ ] Health checks and readiness probes
- [ ] Graceful shutdown handling
- [ ] Metrics exposed for monitoring

## Technical Details

### Service Structure

```
services/reference-data-service/
├── src/
│   ├── main.ts
│   ├── app.module.ts
│   ├── domain/
│   │   ├── instrument.entity.ts
│   │   └── counterparty.entity.ts
│   ├── application/
│   │   ├── instrument.service.ts
│   │   └── counterparty.service.ts
│   ├── infrastructure/
│   │   ├── instrument.repository.ts
│   │   └── counterparty.repository.ts
│   ├── api/
│   │   ├── rest/
│   │   │   ├── instrument.controller.ts
│   │   │   └── counterparty.controller.ts
│   │   └── grpc/
│   │       └── reference-data.grpc.ts
│   └── health/
│       └── health.controller.ts
├── proto/
│   └── reference_data.proto
├── test/
├── Dockerfile
└── package.json
```

### Main Application

```typescript
// services/reference-data-service/src/main.ts
import { NestFactory } from '@nestjs/core';
import { MicroserviceOptions, Transport } from '@nestjs/microservices';
import { AppModule } from './app.module';
import { ValidationPipe } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { join } from 'path';
import { Logger } from '@orion/observability';

async function bootstrap() {
  const logger = new Logger('ReferenceDataService');
  
  // Create HTTP application
  const app = await NestFactory.create(AppModule, {
    logger,
  });

  // Validation
  app.useGlobalPipes(new ValidationPipe({
    whitelist: true,
    transform: true,
  }));

  // Swagger documentation
  const config = new DocumentBuilder()
    .setTitle('Reference Data Service')
    .setDescription('Manage instruments and counterparties')
    .setVersion('1.0')
    .addBearerAuth()
    .build();
  const document = SwaggerModule.createDocument(app, config);
  SwaggerModule.setup('api', app, document);

  // gRPC microservice
  app.connectMicroservice<MicroserviceOptions>({
    transport: Transport.GRPC,
    options: {
      package: 'orion.referencedata.v1',
      protoPath: join(__dirname, '../proto/reference_data.proto'),
      url: `0.0.0.0:${process.env.GRPC_PORT || 50051}`,
    },
  });

  // Graceful shutdown
  app.enableShutdownHooks();

  // Start microservices
  await app.startAllMicroservices();
  logger.log(`gRPC server running on port ${process.env.GRPC_PORT || 50051}`);

  // Start HTTP server
  const port = process.env.HTTP_PORT || 3000;
  await app.listen(port);
  logger.log(`HTTP server running on port ${port}`);
}

bootstrap();
```

### App Module

```typescript
// services/reference-data-service/src/app.module.ts
import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { TenantContextModule } from '@orion/security';
import { DatabaseModule } from '@orion/database';
import { ObservabilityModule } from '@orion/observability';
import { EventBusModule } from '@orion/event-model';

import { InstrumentModule } from './modules/instrument.module';
import { CounterpartyModule } from './modules/counterparty.module';
import { HealthModule } from './health/health.module';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true }),
    TenantContextModule.forRoot({ requireTenant: true }),
    DatabaseModule.forRoot({
      connectionString: process.env.DATABASE_URL!,
    }),
    ObservabilityModule.forRoot({
      serviceName: 'reference-data-service',
    }),
    EventBusModule.forRoot({
      brokers: process.env.KAFKA_BROKERS!.split(','),
      clientId: 'reference-data-service',
    }),
    InstrumentModule,
    CounterpartyModule,
    HealthModule,
  ],
})
export class AppModule {}
```

### gRPC Handler

```typescript
// services/reference-data-service/src/api/grpc/reference-data.grpc.ts
import { Controller } from '@nestjs/common';
import { GrpcMethod } from '@nestjs/microservices';
import { InstrumentService } from '../../application/instrument.service';
import { CounterpartyService } from '../../application/counterparty.service';
import { Metadata } from '@grpc/grpc-js';
import { tenantContextStorage } from '@orion/security';

@Controller()
export class ReferenceDataGrpcController {
  constructor(
    private readonly instrumentService: InstrumentService,
    private readonly counterpartyService: CounterpartyService,
  ) {}

  @GrpcMethod('InstrumentService', 'GetInstrument')
  async getInstrument(data: { id: string }, metadata: Metadata) {
    return this.withTenantContext(metadata, () =>
      this.instrumentService.getInstrument(data.id)
    );
  }

  @GrpcMethod('InstrumentService', 'GetInstrumentBySymbol')
  async getInstrumentBySymbol(data: { symbol: string }, metadata: Metadata) {
    return this.withTenantContext(metadata, () =>
      this.instrumentService.getInstrumentBySymbol(data.symbol)
    );
  }

  @GrpcMethod('InstrumentService', 'ListInstruments')
  async listInstruments(data: any, metadata: Metadata) {
    return this.withTenantContext(metadata, async () => {
      const result = await this.instrumentService.listInstruments(data);
      return { items: result.items, total: result.total };
    });
  }

  @GrpcMethod('CounterpartyService', 'GetCounterparty')
  async getCounterparty(data: { id: string }, metadata: Metadata) {
    return this.withTenantContext(metadata, () =>
      this.counterpartyService.getCounterparty(data.id)
    );
  }

  @GrpcMethod('CounterpartyService', 'GetLiquidityProviders')
  async getLiquidityProviders(data: any, metadata: Metadata) {
    return this.withTenantContext(metadata, async () => {
      const items = await this.counterpartyService.getLiquidityProviders();
      return { items };
    });
  }

  private async withTenantContext<T>(metadata: Metadata, fn: () => Promise<T>): Promise<T> {
    const tenantId = metadata.get('x-tenant-id')[0]?.toString();
    const userId = metadata.get('x-user-id')[0]?.toString();
    const correlationId = metadata.get('x-correlation-id')[0]?.toString() || crypto.randomUUID();

    if (!tenantId) {
      throw new Error('Tenant ID required');
    }

    return tenantContextStorage.run(
      { tenantId, userId, correlationId, roles: [], permissions: [], isAdmin: false },
      fn
    );
  }
}
```

### Health Controller

```typescript
// services/reference-data-service/src/health/health.controller.ts
import { Controller, Get } from '@nestjs/common';
import { HealthCheck, HealthCheckService, TypeOrmHealthIndicator } from '@nestjs/terminus';
import { ApiTags, ApiOperation } from '@nestjs/swagger';

@ApiTags('Health')
@Controller('health')
export class HealthController {
  constructor(
    private health: HealthCheckService,
    private db: TypeOrmHealthIndicator,
  ) {}

  @Get()
  @HealthCheck()
  @ApiOperation({ summary: 'Health check' })
  check() {
    return this.health.check([
      () => this.db.pingCheck('database'),
    ]);
  }

  @Get('ready')
  @ApiOperation({ summary: 'Readiness probe' })
  ready() {
    return { status: 'ready' };
  }

  @Get('live')
  @ApiOperation({ summary: 'Liveness probe' })
  live() {
    return { status: 'alive' };
  }
}
```

### Dockerfile

```dockerfile
# services/reference-data-service/Dockerfile
FROM node:20-alpine AS builder

WORKDIR /app

# Copy workspace files
COPY package*.json ./
COPY nx.json ./
COPY tsconfig*.json ./

# Copy service and libs
COPY services/reference-data-service ./services/reference-data-service
COPY libs ./libs

# Install dependencies
RUN npm ci

# Build
RUN npx nx build reference-data-service --prod

# Production image
FROM node:20-alpine AS runner

WORKDIR /app

# Copy built application
COPY --from=builder /app/dist/services/reference-data-service ./
COPY --from=builder /app/node_modules ./node_modules

# Copy proto files
COPY --from=builder /app/services/reference-data-service/proto ./proto

ENV NODE_ENV=production
ENV HTTP_PORT=3000
ENV GRPC_PORT=50051

EXPOSE 3000 50051

CMD ["node", "main.js"]
```

## Definition of Done

- [ ] Service structure created
- [ ] gRPC and REST working
- [ ] Health endpoints functional
- [ ] Dockerfile builds
- [ ] Swagger docs generated
- [ ] Metrics exposed

## Dependencies

- **US-01-09**: Base Service Template
- **US-01-06**: Protobuf Definitions
