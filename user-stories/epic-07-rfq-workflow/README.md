# Epic 07 - RFQ Workflow

## Epic Overview

| Field | Value |
|-------|-------|
| **Epic ID** | EPIC-07 |
| **Epic Title** | RFQ Workflow |
| **Priority** | P0 - Critical (MVP) |
| **Target Release** | MVP |
| **PRD Reference** | FR-RFQ-01 through FR-RFQ-08 |

## Description

Implement the complete Request for Quote (RFQ) workflow, enabling traders to request quotes from liquidity providers, receive competitive pricing, and execute trades with full audit trails.

## Business Value

- Core trading functionality for the platform
- Competitive pricing from multiple LPs
- Complete audit trail for compliance
- Multi-asset RFQ support (FX, Crypto, Equities)

## User Stories

| Story ID | Title | Priority | Points |
|----------|-------|----------|--------|
| US-07-01 | RFQ Creation and Validation | P0 | 5 |
| US-07-02 | RFQ Distribution to LPs | P0 | 8 |
| US-07-03 | Quote Collection and Ranking | P0 | 8 |
| US-07-04 | Quote Selection and Execution | P0 | 8 |
| US-07-05 | RFQ Timeout and Cancellation | P0 | 5 |
| US-07-06 | RFQ Event Streaming | P0 | 5 |
| US-07-07 | RFQ Audit Trail | P1 | 5 |
| US-07-08 | RFQ Analytics and Reporting | P1 | 5 |

## Workflow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        RFQ Workflow                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐       │
│  │ Trader  │───▶│  Create  │───▶│ Validate │───▶│ Distribute│      │
│  │ Request │    │   RFQ    │    │   RFQ    │    │  to LPs   │      │
│  └─────────┘    └──────────┘    └──────────┘    └─────┬─────┘      │
│                                                       │             │
│                                                       ▼             │
│                      ┌────────────────────────────────────┐        │
│                      │        LP Quote Collection         │        │
│                      │   (Parallel from multiple LPs)     │        │
│                      └────────────────┬───────────────────┘        │
│                                       │                             │
│                                       ▼                             │
│                      ┌────────────────────────────────────┐        │
│                      │        Quote Ranking Engine        │        │
│                      │   (Best Price / Weighted Score)    │        │
│                      └────────────────┬───────────────────┘        │
│                                       │                             │
│         ┌─────────────────────────────┼─────────────────────┐      │
│         ▼                             ▼                     ▼      │
│  ┌─────────────┐              ┌─────────────┐       ┌───────────┐  │
│  │   Timeout   │              │   Select    │       │  Cancel   │  │
│  │  (No quotes)│              │   Quote     │       │    RFQ    │  │
│  └─────────────┘              └──────┬──────┘       └───────────┘  │
│                                      │                              │
│                                      ▼                              │
│                              ┌─────────────┐                        │
│                              │   Execute   │                        │
│                              │   Trade     │                        │
│                              └──────┬──────┘                        │
│                                     │                               │
│                                     ▼                               │
│                              ┌─────────────┐                        │
│                              │  Trade      │                        │
│                              │  Booked     │                        │
│                              └─────────────┘                        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## RFQ State Machine

```
                    ┌─────────────────┐
                    │     PENDING     │ (Initial state after creation)
                    └────────┬────────┘
                             │ validate
                             ▼
                    ┌─────────────────┐
            ┌──────│    VALIDATED    │──────┐
            │      └────────┬────────┘      │
            │               │ distribute    │ reject
            │               ▼               ▼
            │      ┌─────────────────┐  ┌──────────┐
            │      │   DISTRIBUTED   │  │ REJECTED │
            │      └────────┬────────┘  └──────────┘
            │               │
            │    ┌──────────┼──────────┐
            │    │ timeout  │ quote    │ cancel
            │    ▼          ▼          ▼
            │ ┌───────┐ ┌────────┐ ┌───────────┐
            │ │EXPIRED│ │ QUOTED │ │ CANCELLED │
            │ └───────┘ └────┬───┘ └───────────┘
            │                │
            │         ┌──────┴──────┐
            │         │ select      │ timeout
            │         ▼             ▼
            │    ┌──────────┐  ┌───────┐
            │    │ EXECUTED │  │EXPIRED│
            │    └────┬─────┘  └───────┘
            │         │ trade booked
            │         ▼
            │    ┌──────────┐
            └───▶│  FILLED  │
                 └──────────┘
```

## Data Model

```typescript
// RFQ Entity
interface RFQ {
  id: string;
  tenantId: string;
  clientId: string;
  traderId: string;
  
  // Instrument
  instrumentId: string;
  symbol: string;
  assetClass: AssetClass;
  
  // Request details
  side: 'buy' | 'sell' | 'two-way';
  quantity: number;
  notionalCurrency?: string;
  notionalAmount?: number;
  
  // Settlement
  settlementDate?: Date;
  settlementType?: 'spot' | 'forward' | 'same_day';
  
  // Timing
  createdAt: Date;
  expiresAt: Date;
  timeout: number; // seconds
  
  // State
  status: RFQStatus;
  
  // Selected quote
  selectedQuoteId?: string;
  executedTradeId?: string;
  
  // Audit
  version: number;
}

// Quote Entity
interface Quote {
  id: string;
  rfqId: string;
  lpId: string;
  lpName: string;
  
  // Pricing
  bidPrice?: number;
  bidSize?: number;
  askPrice?: number;
  askSize?: number;
  midPrice?: number;
  spread?: number;
  
  // Validity
  validUntil: Date;
  autoQuote: boolean;
  
  // State
  status: QuoteStatus;
  
  // Timestamps
  receivedAt: Date;
  respondedAt?: Date;
}

type RFQStatus = 
  | 'pending'
  | 'validated'
  | 'distributed'
  | 'quoted'
  | 'executed'
  | 'filled'
  | 'expired'
  | 'cancelled'
  | 'rejected';

type QuoteStatus =
  | 'pending'
  | 'received'
  | 'selected'
  | 'rejected'
  | 'expired'
  | 'withdrawn';
```

## Technical Considerations

### Performance Requirements
- RFQ creation to LP distribution: < 50ms
- Quote collection window: configurable (5-60 seconds)
- Quote selection to execution: < 100ms

### Integration Points
- LP Gateway (FIX/REST/WebSocket)
- Market Data Service (reference pricing)
- Trade Execution Service
- Risk Service (pre-trade checks)

## Dependencies

- Epic 04: Reference Data (Instruments)
- Epic 05: Event Bus Infrastructure
- Epic 06: Market Data System
- Epic 08: Trade Execution (downstream)

## Success Metrics

| Metric | Target |
|--------|--------|
| RFQ to quote latency | < 500ms avg |
| Quote fill rate | > 85% |
| RFQ timeout rate | < 5% |
| System availability | 99.9% |
