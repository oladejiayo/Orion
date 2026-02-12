# Event Catalog

> All domain events published to Kafka in the Orion platform. Every event uses the canonical `EventEnvelope<T>` wrapper defined in `libs/event-model`.

---

## Event Envelope Format

```json
{
  "eventId": "uuid",
  "eventType": "TradeExecuted",
  "eventVersion": 1,
  "occurredAt": "2026-02-12T12:34:56.789Z",
  "producer": "execution-service",
  "tenantId": "tenant-001",
  "correlationId": "corr-abc",
  "causationId": "cmd-xyz",
  "entity": {
    "entityType": "Trade",
    "entityId": "trade-123",
    "sequence": 7
  },
  "payload": { ... }
}
```

**Fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `eventId` | UUID | ✅ | Unique event identifier (auto-generated) |
| `eventType` | String | ✅ | Enum value from `EventType` |
| `eventVersion` | Integer | ✅ | Schema version (for backward compatibility) |
| `occurredAt` | Instant (ISO 8601) | ✅ | When the event occurred |
| `producer` | String | ✅ | Service that produced the event |
| `tenantId` | String | ✅ | Tenant scope for isolation |
| `correlationId` | String | ✅ | End-to-end request tracking ID |
| `causationId` | String | ❌ | ID of the event/command that caused this one |
| `entity.entityType` | String | ✅ | Domain entity type (Trade, RFQ, etc.) |
| `entity.entityId` | String | ✅ | Entity identifier |
| `entity.sequence` | Long | ✅ | Monotonic version for ordering |
| `payload` | Object | ✅ | Domain-specific event data |

---

## Event Types by Domain

### Market Data Events

| Event Type | Entity Type | Kafka Topic | Partition Key | Description |
|-----------|------------|-------------|---------------|-------------|
| `MarketTickReceived` | Instrument | `<env>.marketdata.ticks.v1` | instrumentId | Raw tick from source |
| `MarketTickNormalized` | Instrument | `<env>.marketdata.ticks.v1` | instrumentId | Normalized tick |
| `MarketDataStale` | Instrument | `<env>.marketdata.ticks.v1` | instrumentId | Feed heartbeat missed |

### RFQ Events

| Event Type | Entity Type | Kafka Topic | Partition Key | Description |
|-----------|------------|-------------|---------------|-------------|
| `RFQCreated` | RFQ | `<env>.rfq.lifecycle.v1` | rfqId | New RFQ submitted |
| `RFQSent` | RFQ | `<env>.rfq.lifecycle.v1` | rfqId | RFQ routed to LPs |
| `QuoteReceived` | RFQ | `<env>.rfq.quotes.v1` | rfqId | LP quote arrived |
| `QuoteAccepted` | RFQ | `<env>.rfq.lifecycle.v1` | rfqId | User accepted a quote |
| `RFQExpired` | RFQ | `<env>.rfq.lifecycle.v1` | rfqId | RFQ timeout reached |
| `RFQCancelled` | RFQ | `<env>.rfq.lifecycle.v1` | rfqId | User cancelled RFQ |
| `RFQRejected` | RFQ | `<env>.rfq.lifecycle.v1` | rfqId | LP last-look rejected |

### Execution Events

| Event Type | Entity Type | Kafka Topic | Partition Key | Description |
|-----------|------------|-------------|---------------|-------------|
| `TradeExecuted` | Trade | `<env>.execution.trades.v1` | tradeId | Trade confirmed |
| `TradeCancelled` | Trade | `<env>.execution.trades.v1` | tradeId | Trade reversed/cancelled |

### Order Events (V1+)

| Event Type | Entity Type | Kafka Topic | Partition Key | Description |
|-----------|------------|-------------|---------------|-------------|
| `OrderPlaced` | Order | `<env>.oms.orders.v1` | orderId | New order submitted |
| `OrderAcknowledged` | Order | `<env>.oms.orders.v1` | orderId | Order validated & live |
| `OrderFilled` | Order | `<env>.oms.orders.v1` | orderId | Full fill |
| `OrderPartiallyFilled` | Order | `<env>.oms.orders.v1` | orderId | Partial fill |
| `OrderCancelled` | Order | `<env>.oms.orders.v1` | orderId | Cancel confirmed |
| `OrderRejected` | Order | `<env>.oms.orders.v1` | orderId | Validation/risk reject |
| `OrderAmended` | Order | `<env>.oms.orders.v1` | orderId | Price/qty amendment |

### Post-Trade Events

| Event Type | Entity Type | Kafka Topic | Partition Key | Description |
|-----------|------------|-------------|---------------|-------------|
| `TradeConfirmed` | Trade | `<env>.posttrade.settlement.v1` | tradeId | Confirmation generated |
| `SettlementRequested` | Trade | `<env>.posttrade.settlement.v1` | tradeId | Settlement initiated |
| `SettlementCompleted` | Trade | `<env>.posttrade.settlement.v1` | tradeId | Settlement succeeded |
| `SettlementFailed` | Trade | `<env>.posttrade.settlement.v1` | tradeId | Settlement failed |

### Risk Events

| Event Type | Entity Type | Kafka Topic | Partition Key | Description |
|-----------|------------|-------------|---------------|-------------|
| `RiskLimitBreached` | User | `<env>.risk.alerts.v1` | tenantId | Pre-trade limit exceeded |
| `KillSwitchActivated` | Tenant | `<env>.risk.alerts.v1` | tenantId | Trading halted |
| `KillSwitchDeactivated` | Tenant | `<env>.risk.alerts.v1` | tenantId | Trading resumed |

### Admin Events

| Event Type | Entity Type | Kafka Topic | Partition Key | Description |
|-----------|------------|-------------|---------------|-------------|
| `InstrumentCreated` | Instrument | `<env>.admin.changes.v1` | instrumentId | New instrument |
| `InstrumentUpdated` | Instrument | `<env>.admin.changes.v1` | instrumentId | Instrument modified |
| `VenueUpdated` | Venue | `<env>.admin.changes.v1` | venueId | Venue config changed |
| `LPConfigUpdated` | LP | `<env>.admin.changes.v1` | lpId | LP configuration changed |
| `LimitsUpdated` | User | `<env>.admin.changes.v1` | userId | Trading limits changed |

---

## Entity Types

Defined in `EntityType` enum (`libs/event-model`):

| Entity Type | Description |
|------------|-------------|
| `Trade` | An executed trade |
| `RFQ` | A request for quote |
| `Quote` | An LP quote |
| `Order` | A limit/market order |
| `Instrument` | A tradeable instrument |
| `Venue` | A trading venue |
| `LP` | A liquidity provider |
| `User` | A platform user |
| `Tenant` | A client organization |
| `Settlement` | A settlement instruction |

---

## Partitioning & Ordering

Events are partitioned by entity key to guarantee ordering within an entity:

| Topic | Partition Key | Guarantee |
|-------|--------------|-----------|
| Market data ticks | `instrumentId` | All ticks for an instrument are ordered |
| RFQ lifecycle | `rfqId` | All state transitions for an RFQ are ordered |
| RFQ quotes | `rfqId` | All quotes for an RFQ are ordered |
| Orders | `orderId` | All order events are ordered |
| Trades | `tradeId` | All trade events are ordered |
| Settlement | `tradeId` | Settlement events for a trade are ordered |

---

## Schema Evolution Rules

1. **Backward compatible only** within `v1` — add optional fields, never remove/rename
2. **Breaking changes** require new major version (`v2`) with migration period
3. **Consumers must ignore unknown fields** (forward compatibility)
4. **Reserved field numbers** — deleted fields have their numbers reserved to prevent reuse
5. **Event version** field in envelope tracks schema version for consumer routing

---

*Last updated after US-01-06*
