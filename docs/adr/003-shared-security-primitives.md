# ADR-003: Shared Security Primitives as a Pure Domain Library

## Status
**Accepted** — 2026-02-12

## Context
Orion is a multi-tenant trading platform where every API call must be authenticated, authorized, and tenant-isolated. The PRD specifies:
- JWT-based authentication (Cognito/Keycloak)
- Role-based access control (RBAC) with 6 platform roles and a hierarchy
- Attribute-based access control (ABAC) with entitlements per asset class, instrument, and venue
- Strict tenant isolation — no cross-tenant data leakage

The original user story (US-01-04) was designed for TypeScript/Express.js with Express middleware and JWKS-based JWT validation baked into the library.

## Decision
We implement the security library (`orion-security`) as a **pure Java domain library** — framework-agnostic, no Spring dependency. It provides:

1. **Security vocabulary** — Records for `OrionSecurityContext`, `AuthenticatedUser`, `TenantContext`, `Entitlements`, `TradingLimits`
2. **Role hierarchy** — `Role` enum with `implies()` method encoding the PRD role hierarchy
3. **Pure-logic utilities** — `RoleChecker`, `EntitlementChecker`, `TenantIsolationEnforcer`, `BearerTokenExtractor`
4. **Serialization** — `SecurityContextSerializer` for gRPC metadata propagation (JSON + Base64)
5. **Test utilities** — `TestSecurityContextFactory` in `src/main` for cross-module test use

**What this library does NOT do:**
- JWT signature verification (Spring Security Resource Server handles this)
- HTTP filter chains (Spring Security `OncePerRequestFilter` in service modules)
- gRPC interceptor wiring (grpc-java `ServerInterceptor` in service modules)

## Rationale
- **Framework independence** — The library can be used by any Java project, not just Spring Boot services. Consistent with `orion-event-model` approach.
- **Single Responsibility** — Security primitives and business rules live here; transport-layer integration lives in services.
- **Testability** — Pure functions with no HTTP/gRPC dependencies are trivially testable.
- **The Spring Security integration story** — When services are built, they create a Spring `@Configuration` class that wires these primitives into Spring Security's filter chain. The library provides the *what* (rules), services provide the *how* (filter plumbing).

## Consequences
- Services must create their own Spring Security configuration that delegates to this library's utilities
- JWT validation is NOT provided — services use `spring-boot-starter-oauth2-resource-server`
- If a developer forgets to wire `TenantIsolationEnforcer`, tenant isolation is not enforced at the HTTP layer
- Test utilities are in `src/main/java` (not `src/test`), making them available as a regular dependency for other modules' tests
