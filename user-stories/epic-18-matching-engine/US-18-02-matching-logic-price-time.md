# US-18-02: Matching Logic & Price–Time Priority

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-18-02 |
| **Epic** | EPIC-18: Matching Engine (CLOB) |
| **Title** | Matching Logic & Price–Time Priority |
| **Priority** | P2 |
| **Story Points** | 8 |

## User Story

**As a** trader using the CLOB instruments  
**I want** my orders to be matched using strict price–time priority  
**So that** I get predictable and fair execution consistent with exchange-style behavior.

## Acceptance Criteria

### AC1: Price–Time Matching Rules
- **Given** multiple resting orders at different prices
- **When** an incoming contra-side order arrives
- **Then** it matches the **best available price first**, and within the same price level, the **oldest resting order** fills first.

### AC2: Partial Fills & Remaining Quantity
- **Given** an incoming order that is larger than the total available quantity at the best price
- **When** it consumes that level
- **Then** it continues matching at the next best price until fully filled or no more liquidity is available
- And any unfilled remainder behaves according to its `timeInForce` (e.g. `GTC` becomes resting, `IOC` is cancelled, `FOK` cancels entire order).

### AC3: Market vs Limit Orders
- **Given** a MARKET order
- **When** the book has sufficient depth
- **Then** it walks the book across multiple price levels until the requested size is filled or the book is exhausted.

### AC4: Self-Trade Prevention (Optional, Minimal)
- **Given** client configuration for self-trade prevention is enabled
- **When** a new order would match against another resting order from the same `clientId`
- **Then** the engine cancels or avoids the self-match according to a simple policy (e.g. cancel resting or cancel incoming), and records a reason.

## Technical Specification

### Matching Engine Interface

```typescript
// src/clob/domain/MatchingEngine.ts

export interface MatchEvent {
  tradeId: string;
  instrumentId: string;
  makerOrderId: string;
  takerOrderId: string;
  price: bigint;
  quantity: bigint;
  executedAt: bigint; // epoch nanos
}

export interface MatchResult {
  updatedOrders: ClobOrder[];
  trades: MatchEvent[];
}

export interface MatchingEngine {
  // Apply a new incoming order and return resulting trades + updated orders
  match(incoming: ClobOrder, book: OrderBook): MatchResult;
}
```

### Example Matching Algorithm (High-Level)

```typescript
// src/clob/service/SimpleMatchingEngine.ts

export class SimpleMatchingEngine implements MatchingEngine {
  match(incoming: ClobOrder, book: OrderBook): MatchResult {
    const trades: MatchEvent[] = [];
    const now = process.hrtime.bigint();

    if (incoming.side === 'BUY') {
      // Match against asks
      // Pseudo-code: loop best ask levels while price compatible and size remains
    } else {
      // Match against bids
    }

    // Apply time-in-force rules for any remaining quantity
    // - GTC/DAY: accept into book as resting order
    // - IOC: cancel remaining
    // - FOK: if not fully filled, cancel full order and revert partial trades

    return { updatedOrders, trades };
  }
}
```

### Edge Cases to Cover in Tests

- Crossing limit order fully fills immediately and does not rest.
- Multiple matches in one call (e.g. buy order fills against three small asks).
- IOC order with only partial liquidity cancels remainder and does **not** rest.
- FOK order with insufficient liquidity executes **no** trades.
- Matching behavior under identical timestamps (tie-break by sequence or stable order ID ordering).

## Definition of Done

- [ ] Matching engine implemented with deterministic price–time rules.
- [ ] Unit tests for all major edge cases (partial fills, FOK, IOC, market orders).
- [ ] Property-based tests or test vectors verifying determinism under replay.
- [ ] Basic self-trade prevention behavior implemented or explicitly disabled by configuration.
- [ ] Documentation describes matching rules and examples (price ladder illustrations).
