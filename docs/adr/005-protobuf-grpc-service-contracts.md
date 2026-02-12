# ADR-005: Protobuf & gRPC Service Contracts as Maven Module

## Status
Accepted

## Date
2026-02-12

## Context
Orion microservices communicate via gRPC for synchronous, low-latency inter-service calls (market data streaming, RFQ lifecycle, trade queries). We need a single source of truth for all service contracts — the message types, enums, and RPC definitions that every service agrees on.

The original US-01-06 story specified Buf CLI for proto linting and TypeScript code generation. Since Orion's backend is Java 21 + Spring Boot 3.x, we need a Java-native approach.

## Decision
We will create a Maven module `libs/grpc-api` (artifact `orion-grpc-api`) that:

1. **Contains all `.proto` files** under `src/main/proto/v1/` organized by domain (common, marketdata, rfq, execution, posttrade, admin)
2. **Generates Java code at build time** using `protobuf-maven-plugin` (v0.6.1) with:
   - `protoc` (v4.29.3) for Protobuf message classes
   - `protoc-gen-grpc-java` (v1.71.0) for gRPC service stubs
3. **Publishes a single JAR** containing both .proto source files and compiled Java classes, so downstream modules can depend on it as a regular Maven artifact

### What the module provides
- **Common types**: Timestamp, Money, Decimal, TenantContext, UserContext, CorrelationContext, Side/AssetClass enums, Pagination, ErrorResponse
- **5 service contracts**: MarketDataService, RFQService, ExecutionService, PostTradeService, AdminService
- **Generated stubs**: `*ServiceGrpc.java` with ImplBase (server), Stub (async client), BlockingStub (sync client)

### What the module does NOT provide
- gRPC transport runtime (Netty/OkHttp) — service-level dependency
- Spring Boot gRPC integration (grpc-spring-boot-starter) — service-level dependency
- Proto linting (deferred — enforced by conventions and code review)
- Breaking change detection (manual review + v1→v2 versioning policy)

## Rationale
1. **Standard Maven tooling** — `protobuf-maven-plugin` is the de facto standard for Java protobuf projects. No external tools (Buf CLI) needed.
2. **Build-time code generation** — Generated code lives in `target/`, not checked into source control. `mvn compile` regenerates from .proto source of truth.
3. **Single artifact** — One JAR for all contracts. Services declare one dependency, not per-domain dependencies. Simpler dependency graph.
4. **Proto v1 versioning** — All definitions under `v1` package prefix. Breaking changes require `v2` directory and new package names. Within v1, only additive changes allowed.
5. **`java_multiple_files = true`** — Each message/enum is a top-level Java class, not nested inner classes. Better IDE support, cleaner imports.
6. **String-based financial values** — `Decimal.value` and `Money.amount` are strings to preserve arbitrary precision. Services convert to `java.math.BigDecimal`.

## Consequences
1. All services must depend on `orion-grpc-api` for gRPC communication
2. Proto file changes require rebuilding the module (but Maven reactor handles this automatically)
3. Generated code adds ~2-3s to the module's build time (protoc download + compilation)
4. Compiler warnings from generated code are suppressed with `-Xlint:none` in the module's compiler config
5. Services implement `*ServiceGrpc.*ImplBase` for server-side, use `*ServiceGrpc.*BlockingStub` or `*Stub` for client-side
