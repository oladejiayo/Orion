# Epic 18: Matching Engine (CLOB)

## Epic Overview

| Field | Value |
|-------|-------|
| **Epic ID** | EPIC-18 |
| **Epic Name** | Matching Engine (CLOB) |
| **Priority** | P2 – Stretch (V2) |
| **Target Release** | V2 (Post-MVP) |
| **Estimated Effort** | 3–4 sprints |
| **PRD Reference** | §8.9 Matching Engine (CLOB), §6.3 V2 Scope, §19.4 |

## Epic Description

Implement a minimal but production-shaped Central Limit Order Book (CLOB) matching engine for 1–2 flagship instruments. The engine will support price–time priority, maintain in-memory books rebuilt from Kafka event streams, and emit `OrderPlaced` / `OrderFilled` / `TradeExecuted` events compatible with the existing OMS and execution services.

The CLOB is explicitly a **V2 stretch goal** in the PRD, intended to showcase matching-engine design, event sourcing, and replay.

## Goals

1. **Matching Core**: Deterministic price–time priority matching for limit and market orders.
2. **Replayable State**: Order book fully rebuildable from compacted Kafka topics or snapshots + deltas.
3. **Events First**: Order and trade events aligned with the global event catalog and outbox pattern.
4. **Integration**: Clean integration with OMS (Epic 09) and Trade Execution (Epic 08).
5. **Benchmarks**: Demonstrate matching throughput and latency under load (linked to Epic 17).

## Non-Goals

- Not a full exchange-grade engine with all order types or complex routing.
- Not multi-asset; scoped initially to a small set of instruments.
- No full FIX integration here (handled in Epic 19).

## User Stories

| Story ID | Title | Priority | Points |
|----------|-------|----------|--------|
| US-18-01 | CLOB Order Book Model & State Machine | P2 | 8 |
| US-18-02 | Matching Logic & Price–Time Priority | P2 | 8 |
| US-18-03 | CLOB Persistence, Snapshots & Replay | P2 | 5 |
| US-18-04 | CLOB Event Model & Kafka Topics | P2 | 5 |
| US-18-05 | OMS & Execution Service Integration | P2 | 5 |
| US-18-06 | CLOB Monitoring & Performance Benchmarks | P2 | 5 |

## Dependencies

- **Epic 09 – OMS Orders V1**: Order lifecycle & routing.
- **Epic 08 – Trade Execution**: Trade capture and event emission.
- **Epic 05 – Event Bus**: Kafka topics, outbox, idempotency.
- **Epic 10 – Risk & Controls**: Pre-trade checks for CLOB orders.
- **Epic 17 – Testing & Quality**: Performance tests & resilience.

## Success Criteria

- [ ] CLOB supports LIMIT and MARKET orders with price–time priority per instrument.
- [ ] Order book can be fully rebuilt from event streams or snapshot + replay.
- [ ] Events `OrderPlaced`, `OrderAcknowledged`, `OrderFilled`, `TradeExecuted` emitted as per catalog.
- [ ] Integration tests show consistent behavior with OMS/Execution services.
- [ ] Performance benchmark: ≥ 1000 matches/second on a single node in test.
- [ ] Runbook documents replay and failure-handling procedures.
