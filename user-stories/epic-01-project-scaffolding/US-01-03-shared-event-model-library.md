# User Story: US-01-03 - Setup Shared Event Model Library

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-03 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Setup Shared Event Model Library |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **Type** | Technical Foundation |

## User Story

**As a** service developer  
**I want** a shared event model library with canonical event envelopes, serialization, and schema validation  
**So that** all services produce and consume events in a consistent format, enabling correlation, auditing, and replay capabilities

## Description

This story creates the foundational `@orion/event-model` library that defines the canonical event envelope structure, serialization/deserialization utilities, schema validation, and helper functions for event creation. This library is critical for the event-driven architecture and will be used by every service that produces or consumes events.

The event envelope must support:
- Unique event identification
- Event versioning for schema evolution
- Correlation and causation tracking for distributed tracing
- Tenant isolation
- Entity tracking for aggregate roots

## Acceptance Criteria

### AC1: Event Envelope Interface
- [ ] `EventEnvelope<T>` interface is defined matching PRD specification
- [ ] All mandatory fields are documented with JSDoc comments
- [ ] TypeScript strict mode compatibility
- [ ] Generic payload type support

### AC2: Event Envelope Fields
The envelope must include:
- [ ] `eventId`: UUID, unique identifier for the event
- [ ] `eventType`: String, the event type name (e.g., "TradeExecuted")
- [ ] `eventVersion`: Number, schema version for evolution
- [ ] `occurredAt`: ISO 8601 timestamp when event occurred
- [ ] `producer`: String, service that produced the event
- [ ] `tenantId`: String, tenant identifier for isolation
- [ ] `correlationId`: String, ties related events together
- [ ] `causationId`: String, the command/event that caused this event
- [ ] `entity`: Object with entityType, entityId, and sequence number
- [ ] `payload`: Generic type T containing domain-specific data

### AC3: Event Factory Functions
- [ ] `createEvent<T>(params): EventEnvelope<T>` factory function
- [ ] `createEventId(): string` generates UUID v4
- [ ] `createCorrelationId(): string` generates correlation ID
- [ ] Default values applied for optional fields
- [ ] Timestamp generation with millisecond precision

### AC4: Serialization Utilities
- [ ] `serializeEvent(event): string` converts to JSON string
- [ ] `deserializeEvent<T>(json): EventEnvelope<T>` parses JSON
- [ ] Handles date serialization/deserialization correctly
- [ ] Error handling for malformed JSON

### AC5: Schema Validation
- [ ] JSON Schema definitions for event envelope
- [ ] `validateEventEnvelope(event): ValidationResult` function
- [ ] Clear error messages for validation failures
- [ ] Support for validating payload against custom schemas

### AC6: Event Type Registry
- [ ] `EventType` enum or const object for known event types
- [ ] Type guards for specific event types
- [ ] Helper to check if event type is known

### AC7: Testing
- [ ] Unit tests for all factory functions
- [ ] Unit tests for serialization/deserialization
- [ ] Unit tests for validation
- [ ] Test coverage > 90%

### AC8: Library Configuration
- [ ] Library builds with Nx
- [ ] Exports are properly configured in `index.ts`
- [ ] Can be imported as `@orion/event-model`

## Technical Details

### Event Envelope Structure (from PRD)

```typescript
interface EventEnvelope<T = unknown> {
  // Identification
  eventId: string;           // UUID v4
  eventType: string;         // e.g., "TradeExecuted"
  eventVersion: number;      // Schema version, starts at 1
  
  // Timing
  occurredAt: string;        // ISO 8601: "2026-02-09T12:34:56.789Z"
  
  // Source
  producer: string;          // Service name: "execution-service"
  
  // Multi-tenancy
  tenantId: string;          // Tenant identifier
  
  // Correlation & Causation
  correlationId: string;     // Groups related events
  causationId: string;       // What triggered this event
  
  // Entity Tracking
  entity: {
    entityType: string;      // e.g., "Trade", "RFQ", "Order"
    entityId: string;        // Unique ID of the entity
    sequence: number;        // Event sequence for the entity
  };
  
  // Domain Data
  payload: T;
}
```

### Directory Structure

```
/libs/event-model/
├── src/
│   ├── index.ts                 # Public exports
│   ├── interfaces/
│   │   ├── event-envelope.ts    # Core interface
│   │   ├── event-metadata.ts    # Metadata interfaces
│   │   └── validation.ts        # Validation result interface
│   ├── types/
│   │   ├── event-types.ts       # Event type constants
│   │   └── entity-types.ts      # Entity type constants
│   ├── factories/
│   │   ├── event-factory.ts     # Event creation functions
│   │   └── id-generators.ts     # UUID and ID generators
│   ├── serialization/
│   │   ├── serializer.ts        # JSON serialization
│   │   └── deserializer.ts      # JSON deserialization
│   ├── validation/
│   │   ├── envelope-validator.ts
│   │   └── schemas/
│   │       └── event-envelope.schema.json
│   └── utils/
│       └── type-guards.ts       # Type guard functions
├── __tests__/
│   ├── event-factory.spec.ts
│   ├── serialization.spec.ts
│   └── validation.spec.ts
├── project.json                 # Nx project configuration
├── tsconfig.json
├── tsconfig.lib.json
├── tsconfig.spec.json
└── README.md
```

### Key Implementations

#### Event Envelope Interface (`interfaces/event-envelope.ts`)
```typescript
/**
 * Canonical event envelope for all domain events in Orion.
 * All events published to Kafka must use this envelope format.
 * 
 * @template T - The type of the event payload
 */
export interface EventEnvelope<T = unknown> {
  /** Unique identifier for this event instance (UUID v4) */
  eventId: string;
  
  /** The type/name of this event (e.g., "TradeExecuted") */
  eventType: string;
  
  /** Schema version of this event type (for evolution) */
  eventVersion: number;
  
  /** When the event occurred (ISO 8601 with milliseconds) */
  occurredAt: string;
  
  /** Name of the service that produced this event */
  producer: string;
  
  /** Tenant identifier for multi-tenancy isolation */
  tenantId: string;
  
  /** Correlation ID linking related events in a flow */
  correlationId: string;
  
  /** ID of the command or event that caused this event */
  causationId: string;
  
  /** Information about the domain entity this event relates to */
  entity: EventEntity;
  
  /** Domain-specific event data */
  payload: T;
}

export interface EventEntity {
  /** Type of entity (e.g., "Trade", "RFQ", "Order") */
  entityType: string;
  
  /** Unique identifier of the entity */
  entityId: string;
  
  /** Sequence number of this event for the entity (for ordering) */
  sequence: number;
}
```

#### Event Factory (`factories/event-factory.ts`)
```typescript
import { v4 as uuidv4 } from 'uuid';
import { EventEnvelope, EventEntity } from '../interfaces/event-envelope';

export interface CreateEventParams<T> {
  eventType: string;
  eventVersion?: number;
  producer: string;
  tenantId: string;
  correlationId?: string;
  causationId?: string;
  entity: EventEntity;
  payload: T;
}

/**
 * Creates a new event envelope with all required fields populated.
 */
export function createEvent<T>(params: CreateEventParams<T>): EventEnvelope<T> {
  return {
    eventId: uuidv4(),
    eventType: params.eventType,
    eventVersion: params.eventVersion ?? 1,
    occurredAt: new Date().toISOString(),
    producer: params.producer,
    tenantId: params.tenantId,
    correlationId: params.correlationId ?? uuidv4(),
    causationId: params.causationId ?? 'direct',
    entity: params.entity,
    payload: params.payload,
  };
}

/**
 * Creates a child event that inherits correlation context from a parent.
 */
export function createChildEvent<T>(
  parent: EventEnvelope<unknown>,
  params: Omit<CreateEventParams<T>, 'correlationId' | 'tenantId'> & { tenantId?: string }
): EventEnvelope<T> {
  return createEvent({
    ...params,
    tenantId: params.tenantId ?? parent.tenantId,
    correlationId: parent.correlationId,
    causationId: parent.eventId,
  });
}

/**
 * Generates a new event ID (UUID v4).
 */
export function createEventId(): string {
  return uuidv4();
}

/**
 * Generates a new correlation ID (UUID v4).
 */
export function createCorrelationId(): string {
  return uuidv4();
}
```

#### Event Types (`types/event-types.ts`)
```typescript
/**
 * All known event types in the Orion platform.
 * Add new event types here as they are defined.
 */
export const EventTypes = {
  // Market Data Events
  MARKET_TICK_RECEIVED: 'MarketTickReceived',
  MARKET_SNAPSHOT_UPDATED: 'MarketSnapshotUpdated',
  MARKET_DATA_STALE_DETECTED: 'MarketDataStaleDetected',
  
  // RFQ Events
  RFQ_CREATED: 'RFQCreated',
  RFQ_SENT: 'RFQSent',
  QUOTE_RECEIVED: 'QuoteReceived',
  RFQ_EXPIRED: 'RFQExpired',
  QUOTE_ACCEPTED: 'QuoteAccepted',
  RFQ_CANCELLED: 'RFQCancelled',
  QUOTE_ACCEPTANCE_REJECTED: 'QuoteAcceptanceRejected',
  
  // Order Events (V1+)
  ORDER_PLACED: 'OrderPlaced',
  ORDER_ACKNOWLEDGED: 'OrderAcknowledged',
  ORDER_REJECTED: 'OrderRejected',
  ORDER_CANCELLED: 'OrderCancelled',
  ORDER_AMENDED: 'OrderAmended',
  ORDER_FILLED: 'OrderFilled',
  
  // Execution Events
  TRADE_EXECUTED: 'TradeExecuted',
  
  // Post-Trade Events
  TRADE_CONFIRMED: 'TradeConfirmed',
  SETTLEMENT_REQUESTED: 'SettlementRequested',
  SETTLEMENT_COMPLETED: 'SettlementCompleted',
  SETTLEMENT_FAILED: 'SettlementFailed',
  
  // Risk/Admin Events
  RISK_LIMIT_BREACHED: 'RiskLimitBreached',
  KILL_SWITCH_ENABLED: 'KillSwitchEnabled',
  KILL_SWITCH_DISABLED: 'KillSwitchDisabled',
  INSTRUMENT_UPDATED: 'InstrumentUpdated',
  VENUE_UPDATED: 'VenueUpdated',
  LP_CONFIG_UPDATED: 'LPConfigUpdated',
} as const;

export type EventType = typeof EventTypes[keyof typeof EventTypes];

/**
 * Type guard to check if a string is a known event type.
 */
export function isKnownEventType(type: string): type is EventType {
  return Object.values(EventTypes).includes(type as EventType);
}
```

#### Serialization (`serialization/serializer.ts`)
```typescript
import { EventEnvelope } from '../interfaces/event-envelope';

/**
 * Serializes an event envelope to a JSON string.
 * Suitable for publishing to Kafka.
 */
export function serializeEvent<T>(event: EventEnvelope<T>): string {
  return JSON.stringify(event);
}

/**
 * Deserializes a JSON string to an event envelope.
 * @throws Error if JSON is malformed or doesn't match envelope structure
 */
export function deserializeEvent<T>(json: string): EventEnvelope<T> {
  try {
    const parsed = JSON.parse(json);
    
    // Basic structural validation
    if (!parsed.eventId || !parsed.eventType || !parsed.tenantId) {
      throw new Error('Missing required envelope fields');
    }
    
    return parsed as EventEnvelope<T>;
  } catch (error) {
    if (error instanceof SyntaxError) {
      throw new Error(`Invalid JSON: ${error.message}`);
    }
    throw error;
  }
}

/**
 * Safely deserializes an event, returning null on failure.
 */
export function tryDeserializeEvent<T>(json: string): EventEnvelope<T> | null {
  try {
    return deserializeEvent<T>(json);
  } catch {
    return null;
  }
}
```

### Implementation Steps

1. **Create Library Scaffold**
   ```bash
   nx generate @nx/js:library event-model --directory=libs --bundler=esbuild
   ```

2. **Define Interfaces**
   - Create `EventEnvelope` interface
   - Create supporting interfaces
   - Add comprehensive JSDoc documentation

3. **Implement Factories**
   - Create event factory functions
   - Add ID generators
   - Implement child event creation

4. **Implement Serialization**
   - Create serializer functions
   - Handle edge cases
   - Add error handling

5. **Implement Validation**
   - Create JSON Schema
   - Implement validation functions
   - Add helpful error messages

6. **Define Event Types**
   - Create event type constants
   - Create entity type constants
   - Add type guards

7. **Write Tests**
   - Unit tests for all functions
   - Edge case testing
   - Error condition testing

8. **Configure Exports**
   - Set up barrel exports in `index.ts`
   - Verify library builds

## Definition of Done

- [ ] All acceptance criteria are met
- [ ] Library builds without errors
- [ ] All tests pass
- [ ] Test coverage > 90%
- [ ] JSDoc documentation is complete
- [ ] Can be imported as `@orion/event-model`
- [ ] Code review approved
- [ ] README documentation complete

## Dependencies

- US-01-01: Initialize Monorepo

## Downstream Dependencies

- All services that produce or consume events
- US-01-05: Observability library (uses correlation IDs)

## Testing Requirements

### Unit Tests
```typescript
describe('createEvent', () => {
  it('should create event with all required fields', () => {
    const event = createEvent({
      eventType: EventTypes.TRADE_EXECUTED,
      producer: 'execution-service',
      tenantId: 'tenant-001',
      entity: { entityType: 'Trade', entityId: 'trade-123', sequence: 1 },
      payload: { price: 100.5 },
    });
    
    expect(event.eventId).toBeDefined();
    expect(event.eventType).toBe('TradeExecuted');
    expect(event.tenantId).toBe('tenant-001');
    expect(event.occurredAt).toBeDefined();
  });
  
  it('should generate unique event IDs', () => {
    const event1 = createEvent({ /* ... */ });
    const event2 = createEvent({ /* ... */ });
    expect(event1.eventId).not.toBe(event2.eventId);
  });
});

describe('serializeEvent', () => {
  it('should produce valid JSON', () => {
    const event = createEvent({ /* ... */ });
    const json = serializeEvent(event);
    expect(() => JSON.parse(json)).not.toThrow();
  });
});

describe('deserializeEvent', () => {
  it('should round-trip correctly', () => {
    const original = createEvent({ /* ... */ });
    const json = serializeEvent(original);
    const restored = deserializeEvent(json);
    expect(restored).toEqual(original);
  });
  
  it('should throw on invalid JSON', () => {
    expect(() => deserializeEvent('not json')).toThrow();
  });
});
```

## Notes

- UUID v4 is used for event IDs (no ordering guarantee)
- Timestamps use ISO 8601 with millisecond precision
- Event version starts at 1 and increments for breaking changes
- Correlation ID should be passed through entire request flow

## Related Documentation

- [PRD Section 9.3: Event Envelope](../docs/prd/PRD.md#93-event-envelope-mandatory-standard)
- [UUID v4 Specification](https://tools.ietf.org/html/rfc4122)
- [JSON Schema](https://json-schema.org/)
