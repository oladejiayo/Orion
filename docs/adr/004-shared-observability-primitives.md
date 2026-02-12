# ADR-004: Shared Observability Primitives as a Pure Domain Library

## Status
**Accepted** — 2025-07-13

## Context
Orion is a distributed, event-driven trading platform where a single client request may traverse multiple microservices (BFF → RFQ → Execution → Post-Trade). Debugging production issues requires:
- **Correlation IDs** that flow through all logs and traces for a single request
- **Structured logging** with consistent JSON format and automatic context propagation
- **Distributed tracing** via OpenTelemetry for request-level performance visibility
- **Metrics** with tenant-level segmentation for multi-tenant monitoring
- **Health checks** for Kubernetes liveness/readiness probes

The original user story (US-01-05) was designed for TypeScript/Node.js with pino, prom-client, Express middleware, and @opentelemetry/sdk-node.

## Decision
We implement the observability library (`orion-observability`) as a **pure Java domain library** — framework-agnostic, no Spring dependency. It provides:

1. **Correlation context** — `CorrelationContext` record + `CorrelationContextHolder` backed by SLF4J MDC and ThreadLocal for automatic log enrichment
2. **Sensitive data redaction** — `SensitiveDataRedactor` for stripping passwords, tokens, and secrets from log data
3. **Tracing helpers** — `SpanHelper` wrapping OpenTelemetry API for span creation with automatic correlation ID propagation
4. **Metrics factory** — `MetricFactory` wrapping Micrometer's `MeterRegistry` with automatic `tenant` label inclusion on all metrics
5. **Health check abstractions** — `HealthCheck` functional interface, `HealthCheckRegistry` for aggregation, `HealthResult` for probe responses
6. **Test utilities** — `TestCorrelationContextFactory` and `InMemoryHealthCheck` in `src/main` for cross-module test use

**What this library does NOT do:**
- HTTP request filters (Spring MVC `OncePerRequestFilter` in service modules)
- gRPC interceptors (grpc-java `ServerInterceptor` in service modules)
- Kafka producer/consumer instrumentation (Spring Kafka + Micrometer in service modules)
- Spring Boot Actuator health endpoint wiring (service modules)
- OpenTelemetry SDK configuration (OTLP exporter, sampling — service modules)

## Rationale
- **Framework independence** — Micrometer and OpenTelemetry API are framework-agnostic Java libraries. The library works with Spring Boot, Quarkus, Micronaut, or plain Java. Consistent with `orion-event-model` and `orion-security`.
- **Single Responsibility** — Observability primitives and context propagation live here; transport-layer integration (HTTP filters, gRPC interceptors, Kafka instrumentation) lives in services.
- **SLF4J MDC is the Java standard** — MDC automatically attaches context (correlationId, tenantId, userId) to every log statement without explicit parameter passing. Works with any SLF4J backend (Logback, Log4j2).
- **Micrometer over raw Prometheus** — Micrometer is the de facto Java metrics facade, natively supported by Spring Boot. Supports Prometheus, CloudWatch, Datadog, and other backends via registry implementations.
- **OpenTelemetry API only** — Depending on `opentelemetry-api` (not the SDK) keeps the library thin. Services configure the SDK (exporter, sampling, resource attributes) at boot time. Avoids version conflicts and gives services control over their tracing pipeline.
- **Async health checks** — `HealthCheck` returns `CompletableFuture<ComponentHealth>` for non-blocking health probes. Services register checks for their dependencies (Postgres, Redis, Kafka) and the registry aggregates them concurrently.

## Consequences
- Services must configure SLF4J backend (Logback) with JSON encoder for structured logging — the library propagates context via MDC but does not configure the logging backend
- Services must configure OpenTelemetry SDK (OTLP exporter, resource attributes) — the library provides span creation helpers but not SDK initialization
- Services must provide a `MeterRegistry` to `MetricFactory` — typically via Spring Boot auto-configuration
- HTTP/gRPC/Kafka instrumentation requires additional code in each service module
- Test utilities are in `src/main/java` (not `src/test`), making them available as a regular dependency for other modules' tests
