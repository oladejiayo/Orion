# Epic 08 - Trade Execution

## Overview

The Trade Execution epic implements the core trade lifecycle management system, handling trade capture, enrichment, booking, state management, and integration with downstream systems. This forms the central record of all trading activity on the platform.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TRADE EXECUTION FLOW                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   TRADE SOURCES                                                             │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
│   │   RFQ    │  │  Order   │  │  Manual  │  │ External │                   │
│   │  Fill    │  │  Fill    │  │  Trade   │  │  Import  │                   │
│   └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘                   │
│        │             │             │             │                          │
│        └─────────────┴─────────────┴─────────────┘                          │
│                              │                                              │
│                              ▼                                              │
│                    ┌─────────────────┐                                      │
│                    │  Trade Capture  │                                      │
│                    │    Service      │                                      │
│                    └────────┬────────┘                                      │
│                             │                                               │
│                             ▼                                               │
│                    ┌─────────────────┐                                      │
│                    │    Trade        │                                      │
│                    │  Validation     │                                      │
│                    └────────┬────────┘                                      │
│                             │                                               │
│                             ▼                                               │
│                    ┌─────────────────┐                                      │
│                    │    Trade        │                                      │
│                    │  Enrichment     │  ◄── Reference Data                  │
│                    └────────┬────────┘       Market Data                    │
│                             │                                               │
│                             ▼                                               │
│                    ┌─────────────────┐                                      │
│                    │    Trade        │                                      │
│                    │   Repository    │  ◄── PostgreSQL                      │
│                    └────────┬────────┘                                      │
│                             │                                               │
│              ┌──────────────┼──────────────┐                                │
│              │              │              │                                │
│              ▼              ▼              ▼                                │
│      ┌───────────┐  ┌───────────┐  ┌───────────┐                           │
│      │   Event   │  │  Position │  │Settlement │                           │
│      │   Bus     │  │  Update   │  │  Service  │                           │
│      └───────────┘  └───────────┘  └───────────┘                           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Trade State Machine

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TRADE STATE MACHINE                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│                        ┌──────────┐                                         │
│                        │  PENDING │ ◄── Initial capture                    │
│                        └────┬─────┘                                         │
│                             │                                               │
│           ┌─────────────────┼─────────────────┐                            │
│           │                 │                 │                             │
│           ▼                 ▼                 ▼                             │
│   ┌───────────────┐  ┌───────────┐   ┌──────────────┐                      │
│   │   REJECTED    │  │ VALIDATED │   │  CANCELLED   │                      │
│   └───────────────┘  └─────┬─────┘   └──────────────┘                      │
│                            │                                                │
│                            ▼                                                │
│                      ┌───────────┐                                          │
│                      │  BOOKED   │ ◄── Position updated                    │
│                      └─────┬─────┘                                          │
│                            │                                                │
│           ┌────────────────┼────────────────┐                              │
│           │                │                │                               │
│           ▼                ▼                ▼                               │
│   ┌───────────────┐  ┌───────────┐  ┌─────────────┐                        │
│   │   AMENDED     │  │ ALLOCATED │  │  SETTLING   │                        │
│   └───────┬───────┘  └─────┬─────┘  └──────┬──────┘                        │
│           │                │               │                                │
│           └────────────────┼───────────────┘                                │
│                            │                                                │
│                            ▼                                                │
│                      ┌───────────┐                                          │
│                      │  SETTLED  │ ◄── Final state                         │
│                      └───────────┘                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Data Model

### Trade Entity

```typescript
interface Trade {
  // Identity
  id: string;
  tenantId: string;
  tradeRef: string;          // Human-readable reference
  externalRef?: string;      // LP/venue reference
  
  // Source
  sourceType: 'rfq' | 'order' | 'manual' | 'import';
  sourceId?: string;         // RFQ ID, Order ID, etc.
  
  // Counterparties
  clientId: string;
  clientName: string;
  counterpartyId: string;    // LP or venue
  counterpartyName: string;
  
  // Instrument
  instrumentId: string;
  symbol: string;
  assetClass: string;
  
  // Economics
  side: 'buy' | 'sell';
  quantity: number;
  price: number;
  notionalAmount: number;
  currency: string;
  settlementCurrency: string;
  
  // Fees
  commission: number;
  commissionCurrency: string;
  fees: TradeFee[];
  
  // Settlement
  tradeDate: Date;
  settlementDate: Date;
  valueDate: Date;
  
  // State
  status: TradeStatus;
  version: number;
  
  // Allocation
  allocations?: TradeAllocation[];
  
  // Audit
  traderId: string;
  traderName: string;
  executionTime: Date;
  createdAt: Date;
  updatedAt: Date;
}

interface TradeFee {
  type: string;
  amount: number;
  currency: string;
  description?: string;
}

interface TradeAllocation {
  id: string;
  accountId: string;
  accountName: string;
  quantity: number;
  percentage: number;
  status: string;
}
```

### Trade Events

```typescript
// Trade lifecycle events
type TradeEvent =
  | { type: 'trade.captured'; tradeId: string; sourceType: string }
  | { type: 'trade.validated'; tradeId: string }
  | { type: 'trade.rejected'; tradeId: string; reason: string }
  | { type: 'trade.booked'; tradeId: string; positionId: string }
  | { type: 'trade.amended'; tradeId: string; changes: Partial<Trade> }
  | { type: 'trade.cancelled'; tradeId: string; reason: string }
  | { type: 'trade.allocated'; tradeId: string; allocations: TradeAllocation[] }
  | { type: 'trade.settling'; tradeId: string }
  | { type: 'trade.settled'; tradeId: string };
```

## User Stories

| ID | Title | Priority | Points |
|----|-------|----------|--------|
| US-08-01 | Trade Entity and Repository | P0 | 5 |
| US-08-02 | Trade Capture Service | P0 | 5 |
| US-08-03 | Trade Validation Engine | P0 | 5 |
| US-08-04 | Trade Enrichment Pipeline | P1 | 5 |
| US-08-05 | Trade Amendment and Cancellation | P1 | 5 |
| US-08-06 | Trade Allocation | P1 | 5 |
| US-08-07 | Trade Event Publishing | P0 | 3 |

## Integration Points

### Upstream
- **RFQ Service**: Trade creation from filled RFQs
- **Order Service**: Trade creation from filled orders
- **Manual Entry**: Trade capture from UI

### Downstream
- **Position Service**: Position updates on booking
- **Settlement Service**: Settlement instructions
- **Risk Service**: Real-time risk updates
- **Reporting Service**: Trade reports

## Non-Functional Requirements

| Requirement | Target |
|-------------|--------|
| Trade capture latency | < 50ms |
| Validation throughput | 1,000 trades/second |
| Enrichment latency | < 100ms |
| Event publishing | < 10ms |
| Data consistency | Strong (ACID) |

## Dependencies

- **Epic 04**: Reference Data (instruments, counterparties)
- **Epic 05**: Event Bus (trade events)
- **Epic 07**: RFQ Workflow (trade source)
