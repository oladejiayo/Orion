# US-18-04: CLOB Event Model & Kafka Topics

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-18-04 |
| **Epic** | EPIC-18: Matching Engine (CLOB) |
| **Title** | CLOB Event Model & Kafka Topics |
| **Priority** | P2 |
| **Story Points** | 5 |

## User Story

**As a** data engineer and integrator  
**I want** standardized CLOB-related events and Kafka topics  
**So that** other services and analytics can consume order and trade flows consistently.

## Acceptance Criteria

### AC1: Event Types Aligned with Catalog
- **Given** the minimum event catalog in PRD Appendix A
- **When** the CLOB emits events
- **Then** they use the canonical envelope and event types such as:
  - `OrderPlaced`
  - `OrderAcknowledged`
  - `OrderCancelled`
  - `OrderAmended`
  - `OrderFilled`
  - `TradeExecuted`

### AC2: Topic Naming & Partitioning
- **Given** the topic naming rules in §9.4–9.5
- **When** CLOB topics are created
- **Then** they follow `<env>.oms.orders.v1`, `<env>.execution.trades.v1`, `...clob.events.v1`
- And partitioning uses appropriate keys (e.g. `orderId` or `instrumentId`) while preserving per-entity ordering.

### AC3: Schema Definitions
- **Given** the `/schemas/v1` directory
- **When** new CLOB event types are introduced
- **Then** JSON Schemas are added with explicit versioning and backward-compatible evolution rules.

## Technical Specification

### Canonical Envelope (Reuse)

Use existing canonical envelope from PRD §9.3.

### Example `OrderPlaced` Event Payload

```json
{
  "eventId": "uuid-123",
  "eventType": "OrderPlaced",
  "eventVersion": 1,
  "occurredAt": "2026-02-12T12:00:00.000Z",
  "producer": "clob-engine",
  "tenantId": "tenant-001",
  "correlationId": "corr-abc",
  "causationId": "cmd-xyz",
  "entity": {
    "entityType": "Order",
    "entityId": "order-123",
    "sequence": 1
  },
  "payload": {
    "instrumentId": "EURUSD.CLOB",
    "side": "BUY",
    "type": "LIMIT",
    "timeInForce": "GTC",
    "quantity": "1000000",
    "price": "1.08425",
    "clientId": "client-123",
    "sourceOrderId": "oms-order-456"
  }
}
```

## Definition of Done

- [ ] Topics for CLOB orders and trades defined and provisioned.
- [ ] JSON Schemas for CLOB events added under `/schemas/v1`.
- [ ] Producer and consumer libraries extended to handle new event types.
- [ ] Example events documented for use by analytics and downstream services.
