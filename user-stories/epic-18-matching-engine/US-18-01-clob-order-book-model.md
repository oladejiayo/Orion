# US-18-01: CLOB Order Book Model & State Machine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-18-01 |
| **Epic** | EPIC-18: Matching Engine (CLOB) |
| **Title** | CLOB Order Book Model & State Machine |
| **Priority** | P2 |
| **Story Points** | 8 |

## User Story

**As a** matching engine developer  
**I want** a well-defined in-memory order book model and state machine  
**So that** orders can be matched deterministically using price–time priority and replayed from events.

## Acceptance Criteria

### AC1: Order Book Data Structures
- **Given** the CLOB service is running
- **When** multiple orders are submitted for a single instrument
- **Then** the engine maintains separate **bid** and **ask** books
- And each side is ordered by **price** (best first) then **time** (earliest first)
- And orders at the same price are stored in FIFO queues.

### AC2: Order States & Transitions
- **Given** a new order is accepted
- **When** the order progresses through its lifecycle
- **Then** it can only move through valid states: `NEW → WORKING → PARTIALLY_FILLED → FILLED` or `NEW/WORKING → CANCELLED` or `NEW → REJECTED`.

### AC3: Deterministic Invariants
- **Given** identical input order sequences and timestamps
- **When** the engine is replayed from the beginning
- **Then** the resulting book state (open orders, last trade price, depth) is identical.

### AC4: Multi-Instrument Support
- **Given** two instruments are configured for CLOB (e.g., `EUR/USD.CLOB`, `BTC/USD.CLOB`)
- **When** orders are submitted for each instrument
- **Then** each instrument has its own isolated order book instance and state.

## Technical Specification

### Core Entities

```typescript
// src/clob/domain/Order.ts

export type ClobOrderSide = 'BUY' | 'SELL';
export type ClobOrderType = 'LIMIT' | 'MARKET';
export type ClobTimeInForce = 'GTC' | 'DAY' | 'IOC' | 'FOK';

export type ClobOrderStatus =
  | 'NEW'
  | 'WORKING'
  | 'PARTIALLY_FILLED'
  | 'FILLED'
  | 'CANCELLED'
  | 'REJECTED';

export interface ClobOrder {
  orderId: string;
  tenantId: string;
  instrumentId: string; // e.g. "EURUSD.CLOB"
  side: ClobOrderSide;
  type: ClobOrderType;
  timeInForce: ClobTimeInForce;

  quantity: bigint;        // base units
  remainingQuantity: bigint;
  price?: bigint;          // price in ticks for LIMIT

  status: ClobOrderStatus;
  reason?: string;         // rejection/cancel reason

  receivedAt: bigint;      // epoch nanos
  acceptedAt?: bigint;
  updatedAt: bigint;

  // linkage to OMS / external IDs
  clientOrderId?: string;
  sourceOrderId?: string;  // OMS order ID
}

export interface OrderBookLevel {
  price: bigint;
  totalQuantity: bigint;
  orderIds: string[]; // FIFO order IDs at this level
}

export interface OrderBookSnapshot {
  instrumentId: string;
  bids: OrderBookLevel[]; // sorted best bid first
  asks: OrderBookLevel[]; // sorted best ask first
  lastTradePrice?: bigint;
  lastUpdateAt: bigint;
}
```

### Order Book Abstraction

```typescript
// src/clob/domain/OrderBook.ts

export interface OrderBook {
  readonly instrumentId: string;

  // Add or update orders
  accept(order: ClobOrder): void;

  // Mark order as filled/partially filled
  updateFill(orderId: string, filledQuantity: bigint, tradePrice: bigint): void;

  // Cancel order (idempotent)
  cancel(orderId: string, reason?: string): void;

  // Remove fully completed orders from book
  purgeCompleted(): void;

  // Snapshot current state for monitoring and replay validation
  snapshot(): OrderBookSnapshot;
}
```

### State Machine Validation

```typescript
// src/clob/domain/OrderStateMachine.ts

export class OrderStateMachine {
  static transition(current: ClobOrderStatus, event: 'ACCEPT' | 'FILL' | 'PARTIAL_FILL' | 'CANCEL' | 'REJECT'): ClobOrderStatus {
    switch (current) {
      case 'NEW': {
        if (event === 'ACCEPT') return 'WORKING';
        if (event === 'REJECT') return 'REJECTED';
        if (event === 'CANCEL') return 'CANCELLED';
        break;
      }
      case 'WORKING': {
        if (event === 'PARTIAL_FILL') return 'PARTIALLY_FILLED';
        if (event === 'FILL') return 'FILLED';
        if (event === 'CANCEL') return 'CANCELLED';
        break;
      }
      case 'PARTIALLY_FILLED': {
        if (event === 'PARTIAL_FILL') return 'PARTIALLY_FILLED';
        if (event === 'FILL') return 'FILLED';
        if (event === 'CANCEL') return 'CANCELLED';
        break;
      }
      default:
        // Terminal states: no transitions allowed
        return current;
    }

    throw new Error(`Invalid state transition: ${current} + ${event}`);
  }
}
```

### Definition of Done

- [ ] Order book model implemented with clear separation of bids/asks and FIFO queues per price level.
- [ ] Order state machine implemented with exhaustive tests for valid/invalid transitions.
- [ ] Unit tests proving determinism of book state given a fixed sequence of input events.
- [ ] Instrument-scoped order books with clean lifecycle (create/destroy).
- [ ] Documentation describing the CLOB data model and invariants.
