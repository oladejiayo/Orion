# User Story: US-01-06 - Setup Protobuf Definitions and Code Generation

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-01-06 |
| **Epic** | Epic 01 - Project Scaffolding & Foundation Setup |
| **Title** | Setup Protobuf Definitions and Code Generation |
| **Priority** | P0 - Critical |
| **Story Points** | 8 |
| **Type** | Technical Foundation |

## User Story

**As a** service developer  
**I want** well-defined Protobuf schemas with automated code generation  
**So that** all gRPC service contracts are type-safe, versioned, and consistently implemented across services

## Description

This story establishes the Protocol Buffers (Protobuf) schema definitions for all gRPC service interfaces in Orion. It includes setting up the code generation pipeline using buf, creating base message types, and defining service contracts for Market Data, RFQ, Execution, Post-Trade, and Admin services as specified in the PRD.

## Acceptance Criteria

### AC1: Proto Directory Structure
- [ ] `/proto/v1/` directory contains all v1 service definitions
- [ ] Common types are in `/proto/v1/common/`
- [ ] Each domain has its own proto file
- [ ] `buf.yaml` configuration is in `/proto/`

### AC2: Code Generation Setup
- [ ] Buf CLI is configured for code generation
- [ ] `buf.gen.yaml` generates TypeScript/JavaScript stubs
- [ ] Generated code outputs to `/libs/proto-gen/`
- [ ] `npm run proto:generate` regenerates all stubs
- [ ] CI validates proto files with `buf lint` and `buf breaking`

### AC3: Common Message Types
- [ ] `Timestamp` message for consistent time handling
- [ ] `Money` message for financial amounts
- [ ] `Pagination` messages for list operations
- [ ] `Error` message for error responses
- [ ] `TenantContext` message for multi-tenancy

### AC4: Market Data Service Proto
- [ ] `GetSnapshot` RPC defined
- [ ] `StreamTicks` server streaming RPC defined
- [ ] `MarketTick` message with all required fields
- [ ] `SnapshotRequest/Response` messages

### AC5: RFQ Service Proto
- [ ] `CreateRFQ` RPC defined
- [ ] `GetRFQ` RPC defined
- [ ] `AcceptQuote` RPC defined
- [ ] `CancelRFQ` RPC defined
- [ ] `WatchRFQ` server streaming RPC defined
- [ ] All request/response messages defined

### AC6: Execution Service Proto
- [ ] `GetTrade` RPC defined
- [ ] `ListTrades` RPC defined
- [ ] Trade message with all required fields

### AC7: Post-Trade Service Proto
- [ ] `GetConfirmation` RPC defined
- [ ] `GetSettlementStatus` RPC defined
- [ ] Confirmation and settlement messages

### AC8: Admin Service Proto
- [ ] `CreateInstrument` RPC defined
- [ ] `UpdateInstrument` RPC defined
- [ ] `SetKillSwitch` RPC defined
- [ ] `UpdateLimits` RPC defined

### AC9: Best Practices
- [ ] All fields use explicit field numbers
- [ ] Reserved field numbers documented
- [ ] `optional` keyword used for nullable fields
- [ ] Comprehensive comments on all messages and fields

## Technical Details

### Directory Structure

```
/proto/
├── buf.yaml                    # Buf configuration
├── buf.gen.yaml               # Code generation config
├── buf.lock                   # Dependency lock
└── v1/
    ├── common/
    │   ├── types.proto        # Shared types
    │   ├── pagination.proto   # Pagination messages
    │   └── errors.proto       # Error messages
    ├── marketdata/
    │   └── marketdata.proto   # Market Data service
    ├── rfq/
    │   └── rfq.proto          # RFQ service
    ├── execution/
    │   └── execution.proto    # Execution service
    ├── posttrade/
    │   └── posttrade.proto    # Post-Trade service
    ├── admin/
    │   └── admin.proto        # Admin service
    └── oms/
        └── oms.proto          # OMS service (V1+)

/libs/proto-gen/
├── src/
│   ├── index.ts              # Barrel export
│   └── generated/            # Generated code
├── project.json
└── package.json
```

### Buf Configuration (`buf.yaml`)
```yaml
version: v1
name: buf.build/orion/platform
breaking:
  use:
    - FILE
lint:
  use:
    - DEFAULT
  except:
    - PACKAGE_VERSION_SUFFIX
  rpc_allow_same_request_response: false
  rpc_allow_google_protobuf_empty_requests: false
  rpc_allow_google_protobuf_empty_responses: false
```

### Code Generation Config (`buf.gen.yaml`)
```yaml
version: v1
managed:
  enabled: true
  go_package_prefix:
    default: github.com/orion/platform/gen
plugins:
  # TypeScript/JavaScript generation
  - plugin: buf.build/community/timostamm-protobuf-ts
    out: ../libs/proto-gen/src/generated
    opt:
      - long_type_string
      - generate_dependencies
      - ts_nocheck
      - eslint_disable

  # Optional: grpc-js generation
  - plugin: buf.build/grpc/node
    out: ../libs/proto-gen/src/generated
    opt:
      - grpc_js
```

### Common Types (`v1/common/types.proto`)
```protobuf
syntax = "proto3";

package orion.common.v1;

option java_multiple_files = true;
option java_package = "com.orion.common.v1";

// Timestamp with nanosecond precision
message Timestamp {
  // Seconds since Unix epoch
  int64 seconds = 1;
  // Nanoseconds (0-999999999)
  int32 nanos = 2;
}

// Monetary amount with currency
message Money {
  // Amount as string to preserve precision
  string amount = 1;
  // ISO 4217 currency code
  string currency = 2;
}

// Decimal value for financial calculations
message Decimal {
  // Value as string to preserve precision
  string value = 1;
}

// Tenant context for multi-tenancy
message TenantContext {
  // Tenant identifier
  string tenant_id = 1;
  // Optional tenant name
  optional string tenant_name = 2;
}

// User context for audit
message UserContext {
  string user_id = 1;
  string username = 2;
  optional string email = 3;
}

// Correlation context for distributed tracing
message CorrelationContext {
  // Correlation ID linking related requests
  string correlation_id = 1;
  // Causation ID (parent request)
  optional string causation_id = 2;
}

// Side of a trade (buy/sell)
enum Side {
  SIDE_UNSPECIFIED = 0;
  SIDE_BUY = 1;
  SIDE_SELL = 2;
}

// Asset class enumeration
enum AssetClass {
  ASSET_CLASS_UNSPECIFIED = 0;
  ASSET_CLASS_FX = 1;
  ASSET_CLASS_RATES = 2;
  ASSET_CLASS_CREDIT = 3;
  ASSET_CLASS_EQUITIES = 4;
  ASSET_CLASS_COMMODITIES = 5;
}
```

### Pagination (`v1/common/pagination.proto`)
```protobuf
syntax = "proto3";

package orion.common.v1;

// Request pagination parameters
message PaginationRequest {
  // Page number (1-indexed)
  int32 page = 1;
  // Items per page (max 100)
  int32 page_size = 2;
  // Optional cursor for cursor-based pagination
  optional string cursor = 3;
}

// Response pagination info
message PaginationResponse {
  // Current page number
  int32 page = 1;
  // Items per page
  int32 page_size = 2;
  // Total items available
  int64 total_items = 3;
  // Total pages available
  int32 total_pages = 4;
  // Cursor for next page (cursor-based)
  optional string next_cursor = 5;
  // Has more pages
  bool has_next = 6;
}
```

### Market Data Service (`v1/marketdata/marketdata.proto`)
```protobuf
syntax = "proto3";

package orion.marketdata.v1;

import "v1/common/types.proto";

option java_multiple_files = true;
option java_package = "com.orion.marketdata.v1";

// Market Data Query Service
service MarketDataService {
  // Get current snapshot for instruments
  rpc GetSnapshot(SnapshotRequest) returns (SnapshotResponse);
  
  // Subscribe to real-time tick updates (server streaming)
  rpc StreamTicks(TickSubscription) returns (stream MarketTick);
  
  // Get historical ticks for a time range
  rpc GetHistoricalTicks(HistoricalTicksRequest) returns (HistoricalTicksResponse);
}

// Request for market snapshot
message SnapshotRequest {
  // List of instrument IDs to get snapshots for
  repeated string instrument_ids = 1;
  // Include order book depth
  bool include_depth = 2;
  // Depth levels to include (default: 5)
  optional int32 depth_levels = 3;
}

// Response containing market snapshots
message SnapshotResponse {
  // Map of instrument ID to snapshot
  map<string, MarketSnapshot> snapshots = 1;
  // Server timestamp
  orion.common.v1.Timestamp timestamp = 2;
}

// Market snapshot for a single instrument
message MarketSnapshot {
  string instrument_id = 1;
  orion.common.v1.Decimal bid = 2;
  orion.common.v1.Decimal ask = 3;
  orion.common.v1.Decimal mid = 4;
  orion.common.v1.Decimal spread = 5;
  orion.common.v1.Timestamp last_update = 6;
  // Order book depth (if requested)
  optional OrderBookDepth depth = 7;
  // Data quality indicators
  DataQuality quality = 8;
}

// Order book depth
message OrderBookDepth {
  repeated PriceLevel bids = 1;
  repeated PriceLevel asks = 2;
}

message PriceLevel {
  orion.common.v1.Decimal price = 1;
  orion.common.v1.Decimal quantity = 2;
  int32 order_count = 3;
}

// Data quality flags
message DataQuality {
  bool is_stale = 1;
  bool is_indicative = 2;
  optional string source = 3;
  optional int64 age_ms = 4;
}

// Subscription request for streaming ticks
message TickSubscription {
  // Instruments to subscribe to
  repeated string instrument_ids = 1;
  // Tenant context
  orion.common.v1.TenantContext tenant = 2;
  // Correlation context
  orion.common.v1.CorrelationContext correlation = 3;
}

// Single market tick
message MarketTick {
  string instrument_id = 1;
  orion.common.v1.Decimal bid = 2;
  orion.common.v1.Decimal ask = 3;
  orion.common.v1.Decimal mid = 4;
  orion.common.v1.Timestamp timestamp = 5;
  string source = 6;
  int64 sequence = 7;
  DataQuality quality = 8;
}

// Historical ticks request
message HistoricalTicksRequest {
  string instrument_id = 1;
  orion.common.v1.Timestamp start_time = 2;
  orion.common.v1.Timestamp end_time = 3;
  optional int32 max_results = 4;
}

message HistoricalTicksResponse {
  repeated MarketTick ticks = 1;
  bool has_more = 2;
}
```

### RFQ Service (`v1/rfq/rfq.proto`)
```protobuf
syntax = "proto3";

package orion.rfq.v1;

import "v1/common/types.proto";
import "v1/common/pagination.proto";

option java_multiple_files = true;
option java_package = "com.orion.rfq.v1";

// RFQ Service for request-for-quote workflows
service RFQService {
  // Create a new RFQ
  rpc CreateRFQ(CreateRFQRequest) returns (CreateRFQResponse);
  
  // Get RFQ details
  rpc GetRFQ(GetRFQRequest) returns (RFQDetails);
  
  // List RFQs with filters
  rpc ListRFQs(ListRFQsRequest) returns (ListRFQsResponse);
  
  // Accept a quote on an RFQ
  rpc AcceptQuote(AcceptQuoteRequest) returns (AcceptQuoteResponse);
  
  // Cancel an RFQ
  rpc CancelRFQ(CancelRFQRequest) returns (CancelRFQResponse);
  
  // Watch RFQ for updates (server streaming)
  rpc WatchRFQ(WatchRFQRequest) returns (stream RFQUpdate);
}

// RFQ status enumeration
enum RFQStatus {
  RFQ_STATUS_UNSPECIFIED = 0;
  RFQ_STATUS_CREATED = 1;
  RFQ_STATUS_SENT = 2;
  RFQ_STATUS_QUOTING = 3;
  RFQ_STATUS_ACCEPTED = 4;
  RFQ_STATUS_REJECTED = 5;
  RFQ_STATUS_EXPIRED = 6;
  RFQ_STATUS_CANCELLED = 7;
  RFQ_STATUS_TRADED = 8;
}

// Create RFQ request
message CreateRFQRequest {
  string instrument_id = 1;
  orion.common.v1.Side side = 2;
  orion.common.v1.Decimal quantity = 3;
  // Expiry time in seconds from now
  int32 expiry_seconds = 4;
  // Optional minimum size for partial fills
  optional orion.common.v1.Decimal min_quantity = 5;
  // Allow partial fills
  bool allow_partial = 6;
  // Optional specific venue
  optional string venue_id = 7;
  // Idempotency key for retries
  string idempotency_key = 8;
  // Context
  orion.common.v1.TenantContext tenant = 9;
  orion.common.v1.UserContext user = 10;
  orion.common.v1.CorrelationContext correlation = 11;
}

message CreateRFQResponse {
  string rfq_id = 1;
  RFQStatus status = 2;
  orion.common.v1.Timestamp created_at = 3;
  orion.common.v1.Timestamp expires_at = 4;
}

// Get RFQ request
message GetRFQRequest {
  string rfq_id = 1;
  orion.common.v1.TenantContext tenant = 2;
}

// Full RFQ details
message RFQDetails {
  string rfq_id = 1;
  string instrument_id = 2;
  orion.common.v1.Side side = 3;
  orion.common.v1.Decimal quantity = 4;
  RFQStatus status = 5;
  orion.common.v1.Timestamp created_at = 6;
  orion.common.v1.Timestamp expires_at = 7;
  // Collected quotes
  repeated Quote quotes = 8;
  // Accepted quote (if status is ACCEPTED or TRADED)
  optional Quote accepted_quote = 9;
  // Trade ID (if status is TRADED)
  optional string trade_id = 10;
  // Requester info
  string requester_user_id = 11;
}

// Quote on an RFQ
message Quote {
  string quote_id = 1;
  string rfq_id = 2;
  string lp_id = 3;
  string lp_name = 4;
  orion.common.v1.Decimal price = 5;
  orion.common.v1.Decimal quantity = 6;
  orion.common.v1.Timestamp received_at = 7;
  orion.common.v1.Timestamp valid_until = 8;
  bool is_best = 9;
}

// List RFQs request
message ListRFQsRequest {
  orion.common.v1.TenantContext tenant = 1;
  orion.common.v1.PaginationRequest pagination = 2;
  // Filters
  optional string instrument_id = 3;
  repeated RFQStatus statuses = 4;
  optional orion.common.v1.Timestamp from_date = 5;
  optional orion.common.v1.Timestamp to_date = 6;
  optional string user_id = 7;
}

message ListRFQsResponse {
  repeated RFQDetails rfqs = 1;
  orion.common.v1.PaginationResponse pagination = 2;
}

// Accept quote request
message AcceptQuoteRequest {
  string rfq_id = 1;
  string quote_id = 2;
  // Idempotency key
  string idempotency_key = 3;
  orion.common.v1.TenantContext tenant = 4;
  orion.common.v1.UserContext user = 5;
  orion.common.v1.CorrelationContext correlation = 6;
}

message AcceptQuoteResponse {
  bool success = 1;
  RFQStatus new_status = 2;
  optional string error_message = 3;
  optional string trade_id = 4;
}

// Cancel RFQ request
message CancelRFQRequest {
  string rfq_id = 1;
  optional string reason = 2;
  orion.common.v1.TenantContext tenant = 3;
  orion.common.v1.UserContext user = 4;
}

message CancelRFQResponse {
  bool success = 1;
  RFQStatus new_status = 2;
  optional string error_message = 3;
}

// Watch RFQ request
message WatchRFQRequest {
  string rfq_id = 1;
  orion.common.v1.TenantContext tenant = 2;
  orion.common.v1.CorrelationContext correlation = 3;
}

// RFQ update stream message
message RFQUpdate {
  string rfq_id = 1;
  RFQStatus status = 2;
  oneof update {
    Quote new_quote = 3;
    RFQExpired expired = 4;
    QuoteAccepted accepted = 5;
    RFQCancelled cancelled = 6;
  }
}

message RFQExpired {
  orion.common.v1.Timestamp expired_at = 1;
}

message QuoteAccepted {
  string quote_id = 1;
  string trade_id = 2;
}

message RFQCancelled {
  string reason = 1;
  orion.common.v1.Timestamp cancelled_at = 2;
}
```

### Implementation Steps

1. **Install Buf CLI**
   ```bash
   npm install -g @bufbuild/buf
   ```

2. **Create Directory Structure**
   - Create `/proto/v1/` directories
   - Create `buf.yaml` and `buf.gen.yaml`

3. **Define Common Types**
   - Create common message types
   - Create pagination messages
   - Create error messages

4. **Define Service Protos**
   - Market Data service
   - RFQ service
   - Execution service
   - Post-Trade service
   - Admin service

5. **Setup Code Generation**
   - Configure buf.gen.yaml
   - Create proto-gen library scaffold
   - Add npm script for generation

6. **Integrate with CI**
   - Add `buf lint` to CI
   - Add `buf breaking` to CI
   - Verify generated code compiles

7. **Document Usage**
   - Document proto conventions
   - Document code generation process

## Definition of Done

- [ ] All acceptance criteria met
- [ ] `buf lint` passes
- [ ] Code generation produces valid TypeScript
- [ ] Generated code compiles without errors
- [ ] CI validates proto changes
- [ ] Documentation complete
- [ ] Code review approved

## Dependencies

- US-01-01: Initialize Monorepo

## Downstream Dependencies

- All gRPC services depend on generated stubs
- BFF services use generated client stubs
- Domain services implement generated server interfaces

## Notes

- Use `optional` keyword for nullable fields
- Always reserve deleted field numbers
- Keep backward compatibility within v1
- Breaking changes require v2 package
