# Epic 09 - OMS Orders V1

## Epic Overview

| Field | Value |
|-------|-------|
| **Epic ID** | EPIC-09 |
| **Epic Name** | OMS Orders V1 |
| **Priority** | P1 - High |
| **Estimated Duration** | 6 weeks |
| **Dependencies** | Epic 03 (Connectivity), Epic 04 (Reference Data), Epic 08 (Trades) |

## Business Context

The Order Management System (OMS) handles the complete lifecycle of client orders from submission through execution, with support for multiple order types, smart order routing, partial fills, and full audit trail.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         OMS Orders Architecture                          │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────┐    ┌──────────────┐    ┌──────────────┐               │
│  │  Order API  │───▶│ Order Engine │───▶│ Order Router │               │
│  │  (REST/WS)  │    │              │    │              │               │
│  └─────────────┘    └──────────────┘    └──────────────┘               │
│         │                  │                    │                        │
│         ▼                  ▼                    ▼                        │
│  ┌─────────────┐    ┌──────────────┐    ┌──────────────┐               │
│  │  Validation │    │ State Machine│    │  LP Gateway  │               │
│  │   Service   │    │   Manager    │    │  Connectors  │               │
│  └─────────────┘    └──────────────┘    └──────────────┘               │
│         │                  │                    │                        │
│         │                  ▼                    ▼                        │
│         │           ┌──────────────┐    ┌──────────────┐               │
│         │           │  Fill Engine │◀───│ LP Responses │               │
│         │           │              │    │              │               │
│         │           └──────────────┘    └──────────────┘               │
│         │                  │                                            │
│         ▼                  ▼                                            │
│  ┌─────────────────────────────────────────────────┐                   │
│  │                PostgreSQL + Redis                │                   │
│  │         (Orders, Fills, Working State)          │                   │
│  └─────────────────────────────────────────────────┘                   │
│                           │                                             │
│                           ▼                                             │
│  ┌─────────────────────────────────────────────────┐                   │
│  │                  Kafka Events                    │                   │
│  │    (order.created, order.filled, order.done)    │                   │
│  └─────────────────────────────────────────────────┘                   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

## Order State Machine

```
                              ┌──────────┐
                              │  PENDING │
                              └────┬─────┘
                                   │ validate
                                   ▼
          ┌────────────────┬──────────────┬────────────────┐
          │                │              │                │
          ▼                ▼              ▼                ▼
    ┌──────────┐    ┌──────────┐   ┌──────────┐    ┌──────────┐
    │ REJECTED │    │VALIDATED │   │ HELD     │    │ EXPIRED  │
    └──────────┘    └────┬─────┘   └────┬─────┘    └──────────┘
                         │              │
                         │ route        │ release
                         ▼              │
                   ┌──────────┐         │
                   │ WORKING  │◀────────┘
                   └────┬─────┘
                        │
           ┌────────────┼────────────┐
           │            │            │
           ▼            ▼            ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐
    │ PARTIAL  │ │  FILLED  │ │CANCELLED │
    │  FILL    │ │          │ │          │
    └────┬─────┘ └──────────┘ └──────────┘
         │              ▲
         │              │
         └──────────────┘
               complete
```

## Data Model

### Order Entity

```typescript
interface Order {
  id: string;
  tenantId: string;
  orderRef: string;
  clientOrderId?: string;
  
  // Parties
  clientId: string;
  traderId?: string;
  deskId?: string;
  
  // Instrument
  instrumentId: string;
  symbol: string;
  
  // Order Details
  side: 'buy' | 'sell';
  orderType: 'market' | 'limit' | 'stop' | 'stop_limit' | 'pegged';
  timeInForce: 'gtc' | 'day' | 'ioc' | 'fok' | 'gtd';
  
  // Quantities
  quantity: Decimal;
  filledQuantity: Decimal;
  remainingQuantity: Decimal;
  
  // Prices
  price?: Decimal;        // Limit price
  stopPrice?: Decimal;    // Stop trigger price
  pegOffset?: Decimal;    // Peg offset from reference
  
  // Execution
  averagePrice?: Decimal;
  totalFilled?: Decimal;
  fills: OrderFill[];
  
  // Status
  status: OrderStatus;
  statusReason?: string;
  
  // Routing
  routingStrategy: 'best' | 'split' | 'sequential' | 'manual';
  routedLps: string[];
  workingOrderIds: string[];
  
  // Timestamps
  receivedAt: Date;
  validatedAt?: Date;
  workedAt?: Date;
  completedAt?: Date;
  expiresAt?: Date;
  
  // Audit
  version: number;
  createdAt: Date;
  updatedAt: Date;
}
```

### Order Fill Entity

```typescript
interface OrderFill {
  id: string;
  orderId: string;
  fillRef: string;
  
  // Execution
  lpId: string;
  lpOrderId?: string;
  quantity: Decimal;
  price: Decimal;
  
  // Timestamps
  executedAt: Date;
  reportedAt: Date;
  
  // Trade Link
  tradeId?: string;
}
```

## User Stories

| Story ID | Title | Priority | Points |
|----------|-------|----------|--------|
| US-09-01 | Order Entity and Repository | P0 | 5 |
| US-09-02 | Order Types Support | P0 | 5 |
| US-09-03 | Order Validation Engine | P0 | 5 |
| US-09-04 | Order State Machine | P0 | 5 |
| US-09-05 | Order Routing Service | P0 | 8 |
| US-09-06 | Fill Processing Engine | P0 | 5 |
| US-09-07 | Order Modification and Cancellation | P1 | 5 |
| US-09-08 | Order API and WebSocket | P1 | 5 |

## Dependencies

### Upstream
- Epic 03: LP connectivity for order routing
- Epic 04: Instrument/Client reference data
- Epic 08: Trade creation from fills

### Downstream
- Epic 10: Pre-trade risk checks
- Epic 11: Post-trade settlement
- Epic 12: Order analytics

## Success Criteria

- [ ] All order types supported (market, limit, stop, pegged)
- [ ] Order validation < 10ms
- [ ] Fill processing < 50ms
- [ ] State machine handles all transitions
- [ ] Order routing to multiple LPs
- [ ] Complete fill audit trail
- [ ] Order modifications and cancellations
- [ ] Real-time WebSocket updates

## Technical Risks

| Risk | Mitigation |
|------|------------|
| LP connectivity failures | Circuit breaker, failover routing |
| Fill reconciliation issues | Idempotent fill processing, duplicate detection |
| Order state corruption | Optimistic locking, event sourcing |
| Performance under load | Redis for working orders, partitioned DB |
