# Epic 05: Event Bus Infrastructure

## Epic Overview

| Field | Value |
|-------|-------|
| **Epic ID** | EPIC-05 |
| **Epic Name** | Event Bus Infrastructure |
| **Priority** | P0 - Critical |
| **Target Release** | MVP |
| **PRD Reference** | NFR-EVT-01 through NFR-EVT-05 |

## Description

Implement the core event-driven infrastructure using Apache Kafka (MSK in production, Redpanda locally). This includes topic management, the transactional outbox pattern for reliable event publishing, dead-letter queue handling, and idempotent consumers.

## Business Value

- **Reliability**: Guaranteed event delivery with at-least-once semantics
- **Consistency**: Transactional outbox ensures database + event consistency
- **Resilience**: DLQ handling prevents message loss
- **Scalability**: Kafka enables horizontal scaling of event processing

## User Stories

| Story ID | Title | Priority | Points |
|----------|-------|----------|--------|
| US-05-01 | Kafka Topic Configuration | P0 | 5 |
| US-05-02 | Transactional Outbox Pattern | P0 | 8 |
| US-05-03 | Event Publisher Library | P0 | 5 |
| US-05-04 | Event Consumer Library | P0 | 5 |
| US-05-05 | Dead Letter Queue Handling | P0 | 5 |
| US-05-06 | Idempotent Consumer Pattern | P0 | 5 |

## Technical Scope

### Architecture
```
┌─────────────────────────────────────────────────────────────────────┐
│                           Service Layer                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐             │
│  │ RFQ Service │    │Trade Service│    │Market Data  │             │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘             │
│         │                  │                  │                     │
│         ▼                  ▼                  ▼                     │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │                    EventBus Library                          │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │  │
│  │  │  Publisher  │  │  Consumer   │  │  DLQ Handler│          │  │
│  │  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘          │  │
│  └─────────┼────────────────┼────────────────┼──────────────────┘  │
└────────────┼────────────────┼────────────────┼──────────────────────┘
             │                │                │
┌────────────▼────────────────▼────────────────▼──────────────────────┐
│                         Apache Kafka                                │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐        │
│  │ orion.rfq.*    │  │ orion.trade.*  │  │ orion.dlq      │        │
│  │ (partitioned)  │  │ (partitioned)  │  │ (single)       │        │
│  └────────────────┘  └────────────────┘  └────────────────┘        │
└─────────────────────────────────────────────────────────────────────┘
```

### Event Categories
| Domain | Topics |
|--------|--------|
| RFQ | `orion.rfq.created`, `orion.rfq.quoted`, `orion.rfq.executed`, `orion.rfq.expired` |
| Trade | `orion.trade.executed`, `orion.trade.confirmed`, `orion.trade.settled` |
| Order | `orion.order.created`, `orion.order.filled`, `orion.order.cancelled` |
| Market Data | `orion.marketdata.quote`, `orion.marketdata.trade` |
| System | `orion.tenant.*`, `orion.instrument.*`, `orion.dlq` |

## Success Criteria

1. ✅ Topics created with proper partitioning
2. ✅ Outbox ensures transactional consistency
3. ✅ No message loss under failure conditions
4. ✅ DLQ captures failed messages
5. ✅ Idempotent processing prevents duplicates
6. ✅ End-to-end latency < 100ms p95

## Dependencies

- **Epic 01**: Project Scaffolding (shared libraries)
- **Epic 03**: Multi-Tenancy (tenant context in events)

## Acceptance Criteria (Epic Level)

- [ ] All topics configured
- [ ] Outbox relay running
- [ ] Publisher library tested
- [ ] Consumer library tested
- [ ] DLQ operational
- [ ] Idempotency working
- [ ] Monitoring dashboards live
