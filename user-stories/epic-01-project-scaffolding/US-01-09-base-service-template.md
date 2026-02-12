# User Story: US-01-09 - Create Base Service Template

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-09 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Create Base Service Template |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **Type** | Technical Foundation |

## User Story

**As a** service developer  
**I want** a base service template with common patterns pre-configured  
**So that** new services can be created quickly with consistent structure and best practices

## Description

This story creates a template service that demonstrates the standard patterns for Orion services including Express HTTP server, gRPC server, Kafka consumer/producer, database connection, health checks, and observability. New services will be scaffolded from this template.

## Acceptance Criteria

### AC1: Service Structure
- [ ] Standard directory structure for services
- [ ] `main.ts` entry point with graceful shutdown
- [ ] Configuration loading from environment
- [ ] Health check endpoints (`/health`, `/health/ready`, `/health/live`)
- [ ] Metrics endpoint (`/metrics`)

### AC2: HTTP Server (Express)
- [ ] Express server setup with middleware
- [ ] CORS configuration
- [ ] Body parsing (JSON, URL-encoded)
- [ ] Request logging middleware
- [ ] Error handling middleware
- [ ] Correlation ID middleware

### AC3: gRPC Server
- [ ] gRPC server initialization
- [ ] Reflection enabled for development
- [ ] Authentication interceptor
- [ ] Logging interceptor
- [ ] Error handling

### AC4: Kafka Integration
- [ ] Kafka producer setup
- [ ] Kafka consumer setup
- [ ] Consumer group configuration
- [ ] Message serialization/deserialization
- [ ] Error handling and DLQ support

### AC5: Database Integration
- [ ] PostgreSQL connection pool
- [ ] Connection health check
- [ ] Query helpers with logging
- [ ] Transaction support

### AC6: Observability Integration
- [ ] Logger initialized from `@orion/observability`
- [ ] Tracing initialized
- [ ] Metrics registered
- [ ] All components instrumented

### AC7: Configuration
- [ ] Environment-based configuration
- [ ] Configuration validation
- [ ] Secrets loading pattern
- [ ] Default values

### AC8: Testing Setup
- [ ] Unit test examples
- [ ] Integration test examples
- [ ] Test utilities
- [ ] Mocking patterns

## Technical Details

### Directory Structure

```
/services/service-template/
├── src/
│   ├── main.ts                     # Entry point
│   ├── app.ts                      # Express app setup
│   ├── config/
│   │   ├── index.ts                # Configuration loading
│   │   └── schema.ts               # Config validation schema
│   ├── server/
│   │   ├── http-server.ts          # Express server
│   │   ├── grpc-server.ts          # gRPC server
│   │   └── health.ts               # Health check handlers
│   ├── kafka/
│   │   ├── producer.ts             # Kafka producer
│   │   ├── consumer.ts             # Kafka consumer base
│   │   └── handlers/               # Message handlers
│   ├── db/
│   │   ├── pool.ts                 # Database pool
│   │   ├── queries/                # SQL queries
│   │   └── repositories/           # Data repositories
│   ├── grpc/
│   │   ├── services/               # gRPC service implementations
│   │   └── interceptors/           # gRPC interceptors
│   ├── domain/
│   │   ├── entities/               # Domain entities
│   │   ├── events/                 # Domain events
│   │   └── services/               # Domain services
│   └── utils/
│       └── helpers.ts
├── __tests__/
│   ├── unit/
│   ├── integration/
│   └── setup.ts
├── project.json
├── tsconfig.json
├── Dockerfile
└── README.md
```

### Main Entry Point (`src/main.ts`)
```typescript
import { createLogger, initTracing, initMetrics } from '@orion/observability';
import { loadConfig } from './config';
import { createHttpServer } from './server/http-server';
import { createGrpcServer } from './server/grpc-server';
import { createKafkaProducer, createKafkaConsumer } from './kafka';
import { createDatabasePool } from './db/pool';

const logger = createLogger({ serviceName: 'service-template' });

async function main() {
  logger.info('Starting service...');
  
  // Load configuration
  const config = loadConfig();
  
  // Initialize observability
  initTracing({
    serviceName: config.serviceName,
    environment: config.environment,
    otlpEndpoint: config.otlpEndpoint,
    enabled: config.tracingEnabled,
  });
  
  initMetrics({
    serviceName: config.serviceName,
  });
  
  // Initialize database
  const db = await createDatabasePool(config.database);
  
  // Initialize Kafka
  const kafkaProducer = await createKafkaProducer(config.kafka);
  const kafkaConsumer = await createKafkaConsumer(config.kafka);
  
  // Create servers
  const httpServer = createHttpServer({ config, db, kafkaProducer });
  const grpcServer = createGrpcServer({ config, db, kafkaProducer });
  
  // Start servers
  httpServer.listen(config.httpPort, () => {
    logger.info(`HTTP server listening on port ${config.httpPort}`);
  });
  
  grpcServer.start(`0.0.0.0:${config.grpcPort}`, () => {
    logger.info(`gRPC server listening on port ${config.grpcPort}`);
  });
  
  // Start consumers
  await kafkaConsumer.subscribe({ topics: config.kafka.topics });
  await kafkaConsumer.run();
  
  // Graceful shutdown
  const shutdown = async (signal: string) => {
    logger.info(`Received ${signal}, starting graceful shutdown...`);
    
    // Stop accepting new requests
    httpServer.close();
    grpcServer.tryShutdown(() => {});
    
    // Stop Kafka
    await kafkaConsumer.disconnect();
    await kafkaProducer.disconnect();
    
    // Close database
    await db.end();
    
    logger.info('Shutdown complete');
    process.exit(0);
  };
  
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('SIGINT', () => shutdown('SIGINT'));
}

main().catch((error) => {
  logger.error('Failed to start service', { error });
  process.exit(1);
});
```

### Configuration (`src/config/index.ts`)
```typescript
import { z } from 'zod';

const configSchema = z.object({
  serviceName: z.string().default('service-template'),
  environment: z.enum(['development', 'staging', 'production']).default('development'),
  httpPort: z.number().default(3000),
  grpcPort: z.number().default(50051),
  tracingEnabled: z.boolean().default(false),
  otlpEndpoint: z.string().optional(),
  
  database: z.object({
    host: z.string().default('localhost'),
    port: z.number().default(5432),
    database: z.string(),
    user: z.string(),
    password: z.string(),
    maxConnections: z.number().default(10),
  }),
  
  kafka: z.object({
    brokers: z.array(z.string()).default(['localhost:19092']),
    clientId: z.string(),
    groupId: z.string(),
    topics: z.array(z.string()).default([]),
  }),
});

export type Config = z.infer<typeof configSchema>;

export function loadConfig(): Config {
  const config = {
    serviceName: process.env.SERVICE_NAME,
    environment: process.env.NODE_ENV,
    httpPort: parseInt(process.env.HTTP_PORT || '3000'),
    grpcPort: parseInt(process.env.GRPC_PORT || '50051'),
    tracingEnabled: process.env.TRACING_ENABLED === 'true',
    otlpEndpoint: process.env.OTLP_ENDPOINT,
    
    database: {
      host: process.env.DB_HOST,
      port: parseInt(process.env.DB_PORT || '5432'),
      database: process.env.DB_NAME,
      user: process.env.DB_USER,
      password: process.env.DB_PASSWORD,
      maxConnections: parseInt(process.env.DB_MAX_CONNECTIONS || '10'),
    },
    
    kafka: {
      brokers: (process.env.KAFKA_BROKERS || '').split(',').filter(Boolean),
      clientId: process.env.KAFKA_CLIENT_ID,
      groupId: process.env.KAFKA_GROUP_ID,
      topics: (process.env.KAFKA_TOPICS || '').split(',').filter(Boolean),
    },
  };
  
  return configSchema.parse(config);
}
```

### HTTP Server (`src/server/http-server.ts`)
```typescript
import express, { Express, Request, Response, NextFunction } from 'express';
import cors from 'cors';
import helmet from 'helmet';
import {
  createLogger,
  requestLoggingMiddleware,
  correlationMiddleware,
  metricsMiddleware,
  getMetricsOutput,
} from '@orion/observability';
import { authMiddleware } from '@orion/security';
import { createHealthCheck, createPostgresHealthCheck } from '@orion/observability';

interface HttpServerDeps {
  config: any;
  db: any;
  kafkaProducer: any;
}

export function createHttpServer(deps: HttpServerDeps): Express {
  const { config, db } = deps;
  const logger = createLogger({ serviceName: config.serviceName });
  
  const app = express();
  
  // Security middleware
  app.use(helmet());
  app.use(cors({
    origin: config.corsOrigins || '*',
    credentials: true,
  }));
  
  // Body parsing
  app.use(express.json({ limit: '10mb' }));
  app.use(express.urlencoded({ extended: true }));
  
  // Observability middleware
  app.use(correlationMiddleware());
  app.use(requestLoggingMiddleware(logger));
  app.use(metricsMiddleware());
  
  // Health checks (unauthenticated)
  const healthCheck = createHealthCheck({
    database: createPostgresHealthCheck(db),
  });
  
  app.get('/health', async (req, res) => {
    const health = await healthCheck();
    res.status(health.status === 'healthy' ? 200 : 503).json(health);
  });
  
  app.get('/health/live', (req, res) => {
    res.json({ status: 'ok' });
  });
  
  app.get('/health/ready', async (req, res) => {
    const health = await healthCheck();
    res.status(health.status === 'healthy' ? 200 : 503).json(health);
  });
  
  // Metrics endpoint
  app.get('/metrics', async (req, res) => {
    res.set('Content-Type', 'text/plain');
    res.send(await getMetricsOutput());
  });
  
  // Authentication for API routes
  app.use('/api', authMiddleware({
    jwksUri: config.auth.jwksUri,
    issuer: config.auth.issuer,
    audience: config.auth.audience,
  }));
  
  // API routes
  app.use('/api', createApiRouter(deps));
  
  // Error handling
  app.use((err: Error, req: Request, res: Response, next: NextFunction) => {
    logger.error('Unhandled error', {
      error: err.message,
      stack: err.stack,
      path: req.path,
    });
    
    res.status(500).json({
      error: 'Internal server error',
      correlationId: (req as any).correlationId,
    });
  });
  
  return app;
}
```

### Kafka Consumer (`src/kafka/consumer.ts`)
```typescript
import { Kafka, Consumer, EachMessagePayload } from 'kafkajs';
import { createLogger } from '@orion/observability';
import { deserializeEvent, EventEnvelope } from '@orion/event-model';

const logger = createLogger({ serviceName: 'kafka-consumer' });

type MessageHandler<T> = (event: EventEnvelope<T>) => Promise<void>;

interface ConsumerConfig {
  brokers: string[];
  groupId: string;
  clientId: string;
}

export async function createKafkaConsumer(config: ConsumerConfig) {
  const kafka = new Kafka({
    clientId: config.clientId,
    brokers: config.brokers,
  });
  
  const consumer = kafka.consumer({ groupId: config.groupId });
  await consumer.connect();
  
  const handlers = new Map<string, MessageHandler<any>>();
  
  return {
    subscribe: async (options: { topics: string[] }) => {
      await consumer.subscribe({ topics: options.topics, fromBeginning: false });
    },
    
    registerHandler: <T>(eventType: string, handler: MessageHandler<T>) => {
      handlers.set(eventType, handler);
    },
    
    run: async () => {
      await consumer.run({
        eachMessage: async ({ topic, partition, message }: EachMessagePayload) => {
          const value = message.value?.toString();
          if (!value) return;
          
          try {
            const event = deserializeEvent(value);
            const handler = handlers.get(event.eventType);
            
            if (handler) {
              logger.debug('Processing event', {
                eventType: event.eventType,
                eventId: event.eventId,
                correlationId: event.correlationId,
              });
              
              await handler(event);
            } else {
              logger.debug('No handler for event type', { eventType: event.eventType });
            }
          } catch (error) {
            logger.error('Failed to process message', {
              topic,
              partition,
              error: (error as Error).message,
            });
            // TODO: Send to DLQ
          }
        },
      });
    },
    
    disconnect: () => consumer.disconnect(),
  };
}
```

## Implementation Steps

1. Create service directory structure
2. Implement main entry point with graceful shutdown
3. Implement configuration loading and validation
4. Implement HTTP server with all middleware
5. Implement gRPC server with interceptors
6. Implement Kafka producer/consumer
7. Implement database connection
8. Add health checks
9. Write unit tests
10. Write integration tests
11. Document service template

## Definition of Done

- [ ] All acceptance criteria met
- [ ] Service template starts successfully
- [ ] All endpoints respond correctly
- [ ] Tests pass with > 80% coverage
- [ ] Documentation complete
- [ ] Code review approved

## Dependencies

- US-01-03: Event Model Library
- US-01-04: Security Library
- US-01-05: Observability Library
- US-01-06: Protobuf Definitions

## Notes

- This template will be copied and modified for each new service
- Consider creating an Nx generator for new services
- Service should fail fast on configuration errors
