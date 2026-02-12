# ADR-002: Event Envelope as Canonical Event Format

## Status
**Accepted** — 2025-07-12

## Context
Orion is an event-driven platform where services communicate asynchronously via Kafka. Every domain event (trade executed, quote received, risk limit breached) must carry standard metadata for:
- **Correlation**: tracing a user request across multiple services
- **Causation**: knowing which command or event triggered this one
- **Multi-tenancy**: isolating events by tenant
- **Versioning**: evolving event schemas without breaking consumers
- **Ordering**: sequencing events per entity (aggregate root)

We need a shared library defining a canonical event envelope that all services use.

## Decision
We will create `orion-event-model` as a **pure Java library** (no Spring dependencies) containing:

1. **`EventEnvelope<T>`** — a Java record wrapping any domain payload with standard metadata fields: `eventId`, `eventType`, `eventVersion`, `occurredAt`, `producer`, `tenantId`, `correlationId`, `causationId`, `entity`, `payload`.
2. **`EventFactory`** — static factory methods for creating events with sensible defaults (auto-generated UUIDs, current timestamp).
3. **`EventSerializer`** — Jackson-based JSON serialization/deserialization.
4. **`EventValidator`** — validates that all required fields are present and well-formed.
5. **`EventType` / `EntityType`** — enums listing all known event and entity types.

### Key Design Choices
- **Records over classes**: immutability is critical for events — once created, they must not change.
- **Jackson over Protobuf for envelope**: JSON is human-readable and debuggable. Protobuf may wrap inner payloads in future stories.
- **No Spring dependency**: the event model is a pure domain library usable by any Java code.
- **`Instant` over `String` for timestamps**: type safety, nanosecond precision, ISO 8601 via Jackson `JavaTimeModule`.

## Consequences

### Positive
- All services share a consistent event format — no ad-hoc JSON structures
- Correlation/causation enables distributed tracing out of the box
- Versioning field enables safe schema evolution
- Records guarantee immutability

### Negative
- Generic `T` payload requires `TypeReference` for Jackson deserialization (slight verbosity)
- Adding new mandatory fields to the envelope is a breaking change for all producers

### Risks
- If envelope grows too large, Kafka message sizes may need tuning
- Version field must be actively managed per event type

## Alternatives Considered
1. **Protobuf-only events** — Rejected: less debuggable, harder to inspect in Kafka UI, adds protoc compilation step for every change.
2. **CloudEvents spec** — Considered: good standard but adds unnecessary abstraction layer. Our envelope covers all needed fields with simpler Java records.
3. **Separate metadata header vs payload** — Rejected: Kafka headers could hold metadata, but having everything in a single JSON document simplifies consumers and event store queries.
