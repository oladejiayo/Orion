# User Story: US-01-05 - Setup Shared Observability Library

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-05 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Setup Shared Observability Library |
| **Priority** | P0 - Critical |
| **Story Points** | 5 |
| **Type** | Technical Foundation |

## User Story

**As a** service developer and operations engineer  
**I want** a shared observability library with metrics, tracing, and structured logging  
**So that** all services have consistent observability patterns enabling debugging, monitoring, and incident response

## Description

This story creates the `@orion/observability` library providing OpenTelemetry-based tracing, Prometheus-compatible metrics, and structured JSON logging. The library ensures correlation IDs flow through all logs and traces, making it possible to trace a request from the UI through all backend services.

## Acceptance Criteria

### AC1: Structured Logging
- [ ] `createLogger(serviceName)` factory function
- [ ] JSON structured log format
- [ ] Log levels: error, warn, info, debug, trace
- [ ] Automatic inclusion of: timestamp, serviceName, correlationId, tenantId
- [ ] Context propagation for correlation IDs
- [ ] Redaction of sensitive fields (passwords, tokens)

### AC2: Distributed Tracing (OpenTelemetry)
- [ ] `initTracing(config)` initializes OTel tracer
- [ ] `createSpan(name, options)` creates child spans
- [ ] `withSpan(name, fn)` wrapper for automatic span management
- [ ] Automatic context propagation via W3C Trace Context
- [ ] Support for exporting to OTLP endpoint (Jaeger, Zipkin, AWS X-Ray)
- [ ] Correlation ID attached to all spans as attribute

### AC3: Metrics (Prometheus-style)
- [ ] `createCounter(name, description, labels)` 
- [ ] `createGauge(name, description, labels)`
- [ ] `createHistogram(name, description, buckets, labels)`
- [ ] `/metrics` endpoint handler for scraping
- [ ] Default metrics: request count, latency histogram, error count
- [ ] Automatic tenant label inclusion

### AC4: Express Integration
- [ ] `requestLoggingMiddleware()` logs all HTTP requests
- [ ] `tracingMiddleware()` creates spans for HTTP requests
- [ ] `metricsMiddleware()` records request metrics
- [ ] `correlationMiddleware()` extracts/generates correlation ID
- [ ] `errorLoggingMiddleware()` logs errors with stack traces

### AC5: gRPC Integration
- [ ] Tracing interceptor for gRPC calls
- [ ] Logging interceptor for gRPC calls
- [ ] Metrics interceptor for gRPC calls
- [ ] Context propagation via gRPC metadata

### AC6: Kafka Integration
- [ ] `instrumentKafkaProducer(producer)` adds tracing to producer
- [ ] `instrumentKafkaConsumer(consumer)` adds tracing to consumer
- [ ] Correlation ID injection into message headers
- [ ] Consumer lag metrics

### AC7: Health Checks
- [ ] `createHealthCheck(checks)` aggregates health checks
- [ ] `/health` endpoint handler
- [ ] `/health/ready` for readiness probes
- [ ] `/health/live` for liveness probes
- [ ] Configurable health check components

### AC8: Testing
- [ ] Mock logger for testing
- [ ] Test utilities for verifying log output
- [ ] Test utilities for verifying metrics
- [ ] Test coverage > 85%

## Technical Details

### Directory Structure

```
/libs/observability/
├── src/
│   ├── index.ts                    # Public exports
│   ├── logging/
│   │   ├── logger.ts               # Logger factory
│   │   ├── formatters.ts           # Log formatters
│   │   ├── redaction.ts            # Sensitive data redaction
│   │   └── context.ts              # Async context for correlation
│   ├── tracing/
│   │   ├── tracer.ts               # OTel tracer setup
│   │   ├── span-helpers.ts         # Span creation helpers
│   │   ├── propagation.ts          # Context propagation
│   │   └── exporters.ts            # Trace exporters config
│   ├── metrics/
│   │   ├── registry.ts             # Metrics registry
│   │   ├── counters.ts             # Counter helpers
│   │   ├── gauges.ts               # Gauge helpers
│   │   ├── histograms.ts           # Histogram helpers
│   │   └── default-metrics.ts      # Default app metrics
│   ├── middleware/
│   │   ├── express/
│   │   │   ├── request-logging.ts
│   │   │   ├── tracing.ts
│   │   │   ├── metrics.ts
│   │   │   └── correlation.ts
│   │   └── grpc/
│   │       ├── logging.interceptor.ts
│   │       ├── tracing.interceptor.ts
│   │       └── metrics.interceptor.ts
│   ├── kafka/
│   │   ├── producer-instrumentation.ts
│   │   └── consumer-instrumentation.ts
│   ├── health/
│   │   ├── health-check.ts
│   │   └── probes.ts
│   └── testing/
│       ├── mock-logger.ts
│       └── test-helpers.ts
├── __tests__/
├── project.json
├── tsconfig.json
└── README.md
```

### Key Implementations

#### Logger Factory (`logging/logger.ts`)
```typescript
import pino from 'pino';
import { AsyncLocalStorage } from 'async_hooks';
import { redactSensitiveFields } from './redaction';

export interface LogContext {
  correlationId?: string;
  tenantId?: string;
  userId?: string;
  requestId?: string;
  [key: string]: unknown;
}

// Async local storage for context propagation
const asyncContext = new AsyncLocalStorage<LogContext>();

export interface Logger {
  error(msg: string, data?: Record<string, unknown>): void;
  warn(msg: string, data?: Record<string, unknown>): void;
  info(msg: string, data?: Record<string, unknown>): void;
  debug(msg: string, data?: Record<string, unknown>): void;
  trace(msg: string, data?: Record<string, unknown>): void;
  child(bindings: Record<string, unknown>): Logger;
}

export interface LoggerConfig {
  serviceName: string;
  level?: string;
  prettyPrint?: boolean;
}

export function createLogger(config: LoggerConfig): Logger {
  const pinoLogger = pino({
    name: config.serviceName,
    level: config.level || process.env.LOG_LEVEL || 'info',
    transport: config.prettyPrint ? {
      target: 'pino-pretty',
      options: { colorize: true },
    } : undefined,
    formatters: {
      level: (label) => ({ level: label }),
    },
    timestamp: () => `,"timestamp":"${new Date().toISOString()}"`,
    mixin: () => {
      const context = asyncContext.getStore() || {};
      return {
        correlationId: context.correlationId,
        tenantId: context.tenantId,
        userId: context.userId,
      };
    },
    redact: {
      paths: ['password', 'token', 'accessToken', 'refreshToken', 'secret', 'authorization'],
      censor: '[REDACTED]',
    },
  });

  return wrapLogger(pinoLogger);
}

function wrapLogger(pinoLogger: pino.Logger): Logger {
  return {
    error: (msg, data) => pinoLogger.error(redactSensitiveFields(data || {}), msg),
    warn: (msg, data) => pinoLogger.warn(redactSensitiveFields(data || {}), msg),
    info: (msg, data) => pinoLogger.info(redactSensitiveFields(data || {}), msg),
    debug: (msg, data) => pinoLogger.debug(redactSensitiveFields(data || {}), msg),
    trace: (msg, data) => pinoLogger.trace(redactSensitiveFields(data || {}), msg),
    child: (bindings) => wrapLogger(pinoLogger.child(bindings)),
  };
}

/**
 * Run a function with logging context.
 */
export function withLogContext<T>(context: LogContext, fn: () => T): T {
  return asyncContext.run(context, fn);
}

/**
 * Get current logging context.
 */
export function getLogContext(): LogContext | undefined {
  return asyncContext.getStore();
}

/**
 * Set correlation ID in current context.
 */
export function setCorrelationId(correlationId: string): void {
  const context = asyncContext.getStore();
  if (context) {
    context.correlationId = correlationId;
  }
}
```

#### Tracer Setup (`tracing/tracer.ts`)
```typescript
import { NodeSDK } from '@opentelemetry/sdk-node';
import { getNodeAutoInstrumentations } from '@opentelemetry/auto-instrumentations-node';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { Resource } from '@opentelemetry/resources';
import { SemanticResourceAttributes } from '@opentelemetry/semantic-conventions';
import { trace, context, SpanKind, Span } from '@opentelemetry/api';

export interface TracingConfig {
  serviceName: string;
  serviceVersion?: string;
  environment?: string;
  otlpEndpoint?: string;
  enabled?: boolean;
}

let sdk: NodeSDK | null = null;

export function initTracing(config: TracingConfig): void {
  if (!config.enabled && process.env.TRACING_ENABLED !== 'true') {
    console.log('Tracing disabled');
    return;
  }

  const exporter = new OTLPTraceExporter({
    url: config.otlpEndpoint || process.env.OTLP_ENDPOINT || 'http://localhost:4318/v1/traces',
  });

  sdk = new NodeSDK({
    resource: new Resource({
      [SemanticResourceAttributes.SERVICE_NAME]: config.serviceName,
      [SemanticResourceAttributes.SERVICE_VERSION]: config.serviceVersion || '1.0.0',
      [SemanticResourceAttributes.DEPLOYMENT_ENVIRONMENT]: config.environment || 'development',
    }),
    traceExporter: exporter,
    instrumentations: [getNodeAutoInstrumentations()],
  });

  sdk.start();

  process.on('SIGTERM', () => {
    sdk?.shutdown()
      .then(() => console.log('Tracing terminated'))
      .catch((error) => console.log('Error terminating tracing', error))
      .finally(() => process.exit(0));
  });
}

/**
 * Get the current tracer instance.
 */
export function getTracer(name?: string) {
  return trace.getTracer(name || 'orion-default');
}

/**
 * Create a new span and execute a function within it.
 */
export async function withSpan<T>(
  name: string,
  fn: (span: Span) => Promise<T>,
  options?: { kind?: SpanKind; attributes?: Record<string, string> }
): Promise<T> {
  const tracer = getTracer();
  
  return tracer.startActiveSpan(name, { kind: options?.kind }, async (span) => {
    if (options?.attributes) {
      Object.entries(options.attributes).forEach(([key, value]) => {
        span.setAttribute(key, value);
      });
    }
    
    try {
      const result = await fn(span);
      span.setStatus({ code: 1 }); // OK
      return result;
    } catch (error) {
      span.setStatus({ code: 2, message: (error as Error).message }); // ERROR
      span.recordException(error as Error);
      throw error;
    } finally {
      span.end();
    }
  });
}

/**
 * Add correlation ID to current span.
 */
export function setSpanCorrelationId(correlationId: string): void {
  const span = trace.getActiveSpan();
  if (span) {
    span.setAttribute('correlation.id', correlationId);
  }
}
```

#### Metrics (`metrics/registry.ts`)
```typescript
import { Registry, Counter, Gauge, Histogram, collectDefaultMetrics } from 'prom-client';

export interface MetricsConfig {
  serviceName: string;
  prefix?: string;
  defaultLabels?: Record<string, string>;
  collectDefaultMetrics?: boolean;
}

let registry: Registry | null = null;

export function initMetrics(config: MetricsConfig): Registry {
  registry = new Registry();
  
  registry.setDefaultLabels({
    service: config.serviceName,
    ...config.defaultLabels,
  });

  if (config.collectDefaultMetrics !== false) {
    collectDefaultMetrics({ register: registry, prefix: config.prefix });
  }

  return registry;
}

export function getRegistry(): Registry {
  if (!registry) {
    throw new Error('Metrics not initialized. Call initMetrics first.');
  }
  return registry;
}

export function createCounter(
  name: string,
  help: string,
  labelNames: string[] = []
): Counter {
  return new Counter({
    name,
    help,
    labelNames: ['tenant', ...labelNames],
    registers: [getRegistry()],
  });
}

export function createGauge(
  name: string,
  help: string,
  labelNames: string[] = []
): Gauge {
  return new Gauge({
    name,
    help,
    labelNames: ['tenant', ...labelNames],
    registers: [getRegistry()],
  });
}

export function createHistogram(
  name: string,
  help: string,
  buckets: number[] = [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10],
  labelNames: string[] = []
): Histogram {
  return new Histogram({
    name,
    help,
    buckets,
    labelNames: ['tenant', ...labelNames],
    registers: [getRegistry()],
  });
}

/**
 * Get metrics in Prometheus format.
 */
export async function getMetricsOutput(): Promise<string> {
  return getRegistry().metrics();
}
```

#### Express Middleware (`middleware/express/correlation.ts`)
```typescript
import { Request, Response, NextFunction } from 'express';
import { v4 as uuidv4 } from 'uuid';
import { withLogContext, setSpanCorrelationId } from '../../';

const CORRELATION_HEADER = 'x-correlation-id';

/**
 * Middleware to extract or generate correlation ID.
 */
export function correlationMiddleware() {
  return (req: Request, res: Response, next: NextFunction) => {
    const correlationId = (req.headers[CORRELATION_HEADER] as string) || uuidv4();
    
    // Set correlation ID in response header
    res.setHeader(CORRELATION_HEADER, correlationId);
    
    // Attach to request object
    (req as any).correlationId = correlationId;
    
    // Set in tracing span
    setSpanCorrelationId(correlationId);
    
    // Run rest of request with logging context
    withLogContext(
      {
        correlationId,
        tenantId: (req as any).securityContext?.tenant?.tenantId,
        userId: (req as any).securityContext?.user?.userId,
      },
      () => next()
    );
  };
}
```

#### Request Logging Middleware
```typescript
import { Request, Response, NextFunction } from 'express';
import { Logger } from '../../logging/logger';

export function requestLoggingMiddleware(logger: Logger) {
  return (req: Request, res: Response, next: NextFunction) => {
    const startTime = Date.now();
    
    // Log request start
    logger.info('Incoming request', {
      method: req.method,
      path: req.path,
      query: req.query,
      userAgent: req.headers['user-agent'],
    });
    
    // Log response
    res.on('finish', () => {
      const duration = Date.now() - startTime;
      const logFn = res.statusCode >= 400 ? logger.warn : logger.info;
      
      logFn.call(logger, 'Request completed', {
        method: req.method,
        path: req.path,
        statusCode: res.statusCode,
        durationMs: duration,
      });
    });
    
    next();
  };
}
```

#### Health Check (`health/health-check.ts`)
```typescript
export interface HealthCheckResult {
  status: 'healthy' | 'unhealthy' | 'degraded';
  checks: Record<string, ComponentHealth>;
  timestamp: string;
}

export interface ComponentHealth {
  status: 'healthy' | 'unhealthy';
  message?: string;
  latencyMs?: number;
}

export type HealthCheckFn = () => Promise<ComponentHealth>;

export function createHealthCheck(checks: Record<string, HealthCheckFn>) {
  return async (): Promise<HealthCheckResult> => {
    const results: Record<string, ComponentHealth> = {};
    let overallStatus: 'healthy' | 'unhealthy' | 'degraded' = 'healthy';
    
    await Promise.all(
      Object.entries(checks).map(async ([name, checkFn]) => {
        try {
          const startTime = Date.now();
          const result = await checkFn();
          results[name] = {
            ...result,
            latencyMs: Date.now() - startTime,
          };
          
          if (result.status === 'unhealthy') {
            overallStatus = 'unhealthy';
          }
        } catch (error) {
          results[name] = {
            status: 'unhealthy',
            message: (error as Error).message,
          };
          overallStatus = 'unhealthy';
        }
      })
    );
    
    return {
      status: overallStatus,
      checks: results,
      timestamp: new Date().toISOString(),
    };
  };
}

// Common health check implementations
export function createPostgresHealthCheck(pool: any): HealthCheckFn {
  return async () => {
    await pool.query('SELECT 1');
    return { status: 'healthy' };
  };
}

export function createRedisHealthCheck(client: any): HealthCheckFn {
  return async () => {
    await client.ping();
    return { status: 'healthy' };
  };
}

export function createKafkaHealthCheck(admin: any): HealthCheckFn {
  return async () => {
    await admin.listTopics();
    return { status: 'healthy' };
  };
}
```

## Definition of Done

- [ ] All acceptance criteria met
- [ ] Library builds without errors
- [ ] All tests pass with > 85% coverage
- [ ] Documentation complete
- [ ] Can be imported as `@orion/observability`
- [ ] Correlation IDs flow through all components
- [ ] Code review approved

## Dependencies

- US-01-01: Initialize Monorepo
- US-01-03: Event Model Library (for correlation ID consistency)

## Testing Requirements

### Unit Tests
- Test logger output format
- Test metric collection
- Test correlation ID propagation
- Test health check aggregation

### Integration Tests
- Test middleware chain with Express app
- Test trace context propagation
- Test metrics endpoint output

## Notes

- Use pino for logging (fast, structured JSON)
- Use OpenTelemetry for tracing (vendor neutral)
- Use prom-client for metrics (Prometheus compatible)
- Correlation ID is critical for debugging distributed systems
