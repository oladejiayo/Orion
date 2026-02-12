# US-18-03: CLOB Persistence, Snapshots & Replay

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-18-03 |
| **Epic** | EPIC-18: Matching Engine (CLOB) |
| **Title** | CLOB Persistence, Snapshots & Replay |
| **Priority** | P2 |
| **Story Points** | 5 |

## User Story

**As a** platform engineer  
**I want** the CLOB to be fully rebuildable from persisted events and periodic snapshots  
**So that** I can recover from failures, run simulations, and comply with audit requirements.

## Acceptance Criteria

### AC1: Event-Sourced Order & Trade Streams
- **Given** orders and trades processed by the CLOB
- **When** they are persisted to Kafka topics and Postgres
- **Then** there exist well-defined compacted topics (e.g. `...orders.book.v1`) and/or append-only topics (`...orders.events.v1`, `...trades.v1`) that represent the full lifecycle.

### AC2: Periodic Book Snapshots
- **Given** active instruments
- **When** the scheduler runs at configured intervals (e.g., every 5 minutes)
- **Then** the CLOB persists a snapshot of each order book (bids, asks, last trade) to Postgres or S3 with an associated snapshot ID and timestamp.

### AC3: Replay Procedure
- **Given** a catastrophic failure or simulation request
- **When** an operator triggers a replay from a particular snapshot ID
- **Then** the engine restores the book from that snapshot and replays subsequent events from Kafka to reach a consistent current state.

### AC4: Deterministic Rebuild
- **Given** two independent replays from the same snapshot + event range
- **When** they complete
- **Then** the resulting order book snapshots (and aggregate metrics) are identical.

## Technical Specification

### Storage Schema (Example)

```sql
-- Snapshot table
CREATE TABLE clob_order_book_snapshot (
    snapshot_id      UUID PRIMARY KEY,
    instrument_id    TEXT NOT NULL,
    snapshot_seq     BIGINT NOT NULL,
    taken_at         TIMESTAMPTZ NOT NULL,
    bids_json        JSONB NOT NULL,
    asks_json        JSONB NOT NULL,
    last_trade_price NUMERIC(20, 8),
    metadata         JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_clob_snapshot_instrument_time
  ON clob_order_book_snapshot (instrument_id, taken_at DESC);
```

### Event Topics

- `env.clob.orders.events.v1` – append-only order events (`OrderPlaced`, `OrderCancelled`, `OrderAmended` etc.)
- `env.clob.trades.v1` – append-only trade events from the CLOB matches
- Optionally `env.clob.orderbook.snapshot.v1` – compacted topic for the latest snapshot per instrument

### Replay CLI Sketch

```bash
# scripts/clob-replay.sh

# Usage: clob-replay.sh <instrumentId> <snapshotId> <fromOffset>

INSTRUMENT_ID=$1
SNAPSHOT_ID=$2
FROM_OFFSET=$3

# 1. Load snapshot from DB
# 2. Start a CLOB engine instance in replay mode
# 3. Consume events from Kafka from the given offset onwards
# 4. Rebuild book and optionally compare with current prod state
```

## Definition of Done

- [ ] Order and trade events for CLOB are published to dedicated Kafka topics.
- [ ] Order book snapshot schema defined and implemented.
- [ ] Snapshot writer implemented with configurable cadence.
- [ ] Replay routine implemented and verified via automated integration test.
- [ ] Runbook updated with step-by-step replay procedure and failure modes.
