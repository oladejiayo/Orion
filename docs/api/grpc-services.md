# gRPC Services Reference

> Complete reference for all gRPC service contracts defined in `libs/grpc-api`. Proto files are the single source of truth; this document provides a human-readable overview.

---

## Proto File Locations

All definitions live under `libs/grpc-api/src/main/proto/v1/`:

```
v1/
├── common/
│   ├── types.proto          ← Shared types: Timestamp, Money, Decimal, Side, AssetClass, contexts
│   ├── pagination.proto     ← PaginationRequest / PaginationResponse
│   └── errors.proto         ← ErrorDetail / ErrorResponse
├── marketdata/
│   └── marketdata.proto     ← MarketDataService (3 RPCs)
├── rfq/
│   └── rfq.proto            ← RFQService (6 RPCs)
├── execution/
│   └── execution.proto      ← ExecutionService (2 RPCs)
├── posttrade/
│   └── posttrade.proto      ← PostTradeService (3 RPCs)
└── admin/
    └── admin.proto           ← AdminService (6 RPCs)
```

**Maven dependency:**
```xml
<dependency>
    <groupId>com.orion</groupId>
    <artifactId>orion-grpc-api</artifactId>
</dependency>
```

---

## 1. Common Types (`v1/common/`)

### types.proto

| Message/Enum | Java Class | Description |
|-------------|-----------|-------------|
| `Timestamp` | `com.orion.common.v1.Timestamp` | seconds (int64) + nanos (int32) |
| `Money` | `com.orion.common.v1.Money` | String amount + currency (preserves arbitrary precision) |
| `Decimal` | `com.orion.common.v1.Decimal` | String value → `BigDecimal` in services |
| `TenantContext` | `com.orion.common.v1.TenantContext` | tenant_id + optional tenant_name |
| `UserContext` | `com.orion.common.v1.UserContext` | user_id + username + optional email |
| `CorrelationContext` | `com.orion.common.v1.CorrelationContext` | correlation_id + optional causation_id |
| `Side` | `com.orion.common.v1.Side` | UNSPECIFIED / BUY / SELL |
| `AssetClass` | `com.orion.common.v1.AssetClass` | UNSPECIFIED / FX / RATES / CREDIT / EQUITIES / COMMODITIES |

### pagination.proto

| Message | Description |
|---------|-------------|
| `PaginationRequest` | page, page_size, optional cursor |
| `PaginationResponse` | page, page_size, total_items, total_pages, optional next_cursor, has_next |

### errors.proto

| Message | Description |
|---------|-------------|
| `ErrorDetail` | code, message, optional field, map<string,string> metadata |
| `ErrorResponse` | error_code, message, repeated ErrorDetail, correlation_id, Timestamp |

---

## 2. MarketDataService

**Proto:** `v1/marketdata/marketdata.proto`
**Java package:** `com.orion.marketdata.v1`
**gRPC class:** `MarketDataServiceGrpc`

| RPC | Type | Request | Response |
|-----|------|---------|----------|
| `GetSnapshot` | Unary | `SnapshotRequest` | `SnapshotResponse` |
| `StreamTicks` | Server Streaming | `TickSubscription` | `stream MarketTick` |
| `GetHistoricalTicks` | Unary | `HistoricalTicksRequest` | `HistoricalTicksResponse` |

### Key Messages

| Message | Notable Fields |
|---------|---------------|
| `SnapshotRequest` | repeated instrument_ids, include_depth, optional depth_levels |
| `SnapshotResponse` | map<string, MarketSnapshot> snapshots, Timestamp |
| `MarketSnapshot` | instrument_id, bid, ask, mid, spread, last_update, optional depth, quality |
| `OrderBookDepth` | repeated PriceLevel bids, repeated PriceLevel asks |
| `TickSubscription` | repeated instrument_ids, TenantContext, CorrelationContext |
| `MarketTick` | instrument_id, bid, ask, mid, timestamp, source, sequence, quality |

---

## 3. RFQService

**Proto:** `v1/rfq/rfq.proto`
**Java package:** `com.orion.rfq.v1`
**gRPC class:** `RFQServiceGrpc`

| RPC | Type | Request | Response |
|-----|------|---------|----------|
| `CreateRFQ` | Unary | `CreateRFQRequest` | `CreateRFQResponse` |
| `GetRFQ` | Unary | `GetRFQRequest` | `RFQDetails` |
| `ListRFQs` | Unary | `ListRFQsRequest` | `ListRFQsResponse` |
| `AcceptQuote` | Unary | `AcceptQuoteRequest` | `AcceptQuoteResponse` |
| `CancelRFQ` | Unary | `CancelRFQRequest` | `CancelRFQResponse` |
| `WatchRFQ` | Server Streaming | `WatchRFQRequest` | `stream RFQUpdate` |

### RFQStatus Enum

| Value | Number | Description |
|-------|--------|-------------|
| `RFQ_STATUS_UNSPECIFIED` | 0 | Default/unset |
| `RFQ_STATUS_CREATED` | 1 | RFQ submitted |
| `RFQ_STATUS_SENT` | 2 | Routed to LPs |
| `RFQ_STATUS_QUOTING` | 3 | Collecting quotes |
| `RFQ_STATUS_ACCEPTED` | 4 | Quote accepted |
| `RFQ_STATUS_REJECTED` | 5 | LP last-look reject |
| `RFQ_STATUS_EXPIRED` | 6 | Timeout reached |
| `RFQ_STATUS_CANCELLED` | 7 | User cancelled |
| `RFQ_STATUS_TRADED` | 8 | Trade confirmed |

### RFQUpdate `oneof update`

| Field | Type | When |
|-------|------|------|
| `new_quote` | `Quote` | New quote received from LP |
| `expired` | `RFQExpired` | RFQ timed out |
| `accepted` | `QuoteAccepted` | Quote was accepted, trade created |
| `cancelled` | `RFQCancelled` | User cancelled |

### Key Design Notes

- `CreateRFQRequest` includes `idempotency_key` for safe retries
- `TenantContext`, `UserContext`, `CorrelationContext` on all write requests
- `ListRFQsRequest` supports filtering by instrument, side, status, and date range

---

## 4. ExecutionService

**Proto:** `v1/execution/execution.proto`
**Java package:** `com.orion.execution.v1`
**gRPC class:** `ExecutionServiceGrpc`

| RPC | Type | Request | Response |
|-----|------|---------|----------|
| `GetTrade` | Unary | `GetTradeRequest` | `TradeDetails` |
| `ListTrades` | Unary | `ListTradesRequest` | `ListTradesResponse` |

### TradeStatus Enum

| Value | Number | Description |
|-------|--------|-------------|
| `TRADE_STATUS_UNSPECIFIED` | 0 | Default/unset |
| `TRADE_STATUS_PENDING` | 1 | Awaiting execution |
| `TRADE_STATUS_EXECUTED` | 2 | Trade completed |
| `TRADE_STATUS_PARTIALLY_FILLED` | 3 | Partial execution |
| `TRADE_STATUS_CANCELLED` | 4 | Trade cancelled |
| `TRADE_STATUS_REJECTED` | 5 | Execution rejected |
| `TRADE_STATUS_SETTLED` | 6 | Settlement complete |

### Key Design Notes

- `TradeDetails.rfq_id` is optional (absent for CLOB-originated trades)
- `TradeDetails` includes counterparty info, notional, timestamps, and context
- `ListTradesRequest` supports filtering by instrument, side, status, date range

---

## 5. PostTradeService

**Proto:** `v1/posttrade/posttrade.proto`
**Java package:** `com.orion.posttrade.v1`
**gRPC class:** `PostTradeServiceGrpc`

| RPC | Type | Request | Response |
|-----|------|---------|----------|
| `GetConfirmation` | Unary | `GetConfirmationRequest` | `ConfirmationDetails` |
| `GetSettlementStatus` | Unary | `GetSettlementStatusRequest` | `SettlementDetails` |
| `ListConfirmations` | Unary | `ListConfirmationsRequest` | `ListConfirmationsResponse` |

### Enums

**ConfirmationStatus:** UNSPECIFIED / PENDING / SENT / AFFIRMED / DISPUTED

**SettlementStatus:** UNSPECIFIED / PENDING / INSTRUCTED / MATCHED / SETTLED / FAILED

### Key Design Notes

- `SettlementDetails.settlement_amount` uses `Money` (string-based amount + currency)
- `SettlementDetails.failure_reason` is optional (present only for FAILED status)

---

## 6. AdminService

**Proto:** `v1/admin/admin.proto`
**Java package:** `com.orion.admin.v1`
**gRPC class:** `AdminServiceGrpc`

| RPC | Type | Request | Response |
|-----|------|---------|----------|
| `CreateInstrument` | Unary | `CreateInstrumentRequest` | `InstrumentDetails` |
| `UpdateInstrument` | Unary | `UpdateInstrumentRequest` | `InstrumentDetails` |
| `GetInstrument` | Unary | `GetInstrumentRequest` | `InstrumentDetails` |
| `ListInstruments` | Unary | `ListInstrumentsRequest` | `ListInstrumentsResponse` |
| `SetKillSwitch` | Unary | `SetKillSwitchRequest` | `SetKillSwitchResponse` |
| `UpdateLimits` | Unary | `UpdateLimitsRequest` | `UpdateLimitsResponse` |

### InstrumentStatus Enum

UNSPECIFIED / ACTIVE / SUSPENDED / DELISTED

### Key Design Notes

- `UpdateInstrumentRequest` supports partial updates (only set fields are changed)
- `InstrumentDetails` optional fields: price_precision, min_quantity, max_quantity, tick_size
- `SetKillSwitchRequest` includes optional reason for audit trail
- `UpdateLimitsRequest` fields: max_notional, max_open_orders, max_requests_per_second (all optional for partial update)

---

## 7. Versioning Strategy

| Rule | Detail |
|------|--------|
| All contracts under `v1/` package | Breaking changes require `v2/` |
| Additive-only within v1 | New fields, new RPCs, new enum values OK |
| No removing/renaming fields | Reserve deleted field numbers to prevent reuse |
| `optional` for nullable fields | Generates `hasXxx()` in Java |
| String-based financial amounts | `Decimal.value` and `Money.amount` → `BigDecimal` in services |

---

## 8. Code Generation

The `protobuf-maven-plugin` (0.6.1) compiles proto files at build time:

```
mvn compile -pl libs/grpc-api
```

This produces:
- **protobuf-java** message classes (132 files) in `target/generated-sources/protobuf/java/`
- **grpc-java** service stubs in `target/generated-sources/protobuf/grpc-java/`

Generated stubs include:
- `*Grpc.java` — Service descriptor + `ImplBase` (server), `Stub` (async client), `BlockingStub` (sync client)
- Per-message Java classes with builders, serialization, and `hasXxx()` for optional fields

---

*Last updated after US-01-06*
