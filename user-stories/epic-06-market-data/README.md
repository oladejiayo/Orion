# Epic 06 - Market Data System

## Epic Overview

| Field | Value |
|-------|-------|
| **Epic ID** | EPIC-06 |
| **Epic Title** | Market Data System |
| **Priority** | P0 - Critical (MVP) |
| **Target Release** | MVP |
| **PRD Reference** | FR-MD-01, FR-MD-02, FR-MD-03 |

## Description

Build a real-time market data infrastructure supporting multiple asset classes with pub/sub streaming, price normalization, aggregation, and distribution to clients via WebSocket and Server-Sent Events.

## Business Value

- Real-time price discovery for trading decisions
- Aggregated views across liquidity providers
- Historical data for analytics and backtesting
- Multi-asset support (FX, Crypto, Equities, Fixed Income)

## User Stories

| Story ID | Title | Priority | Points |
|----------|-------|----------|--------|
| US-06-01 | Market Data Ingestion Service | P0 | 8 |
| US-06-02 | Price Normalization Layer | P0 | 5 |
| US-06-03 | Best Price Aggregation Engine | P0 | 8 |
| US-06-04 | Market Data Distribution (WebSocket) | P0 | 8 |
| US-06-05 | Market Data Subscription Manager | P0 | 5 |
| US-06-06 | Historical Data Service | P1 | 5 |
| US-06-07 | Market Data Conflation | P1 | 5 |

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Market Data System                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐          │
│  │   LP Feed    │    │   LP Feed    │    │   LP Feed    │          │
│  │   (FIX/WS)   │    │   (REST)     │    │   (gRPC)     │          │
│  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘          │
│         │                   │                   │                   │
│         └───────────────────┼───────────────────┘                   │
│                             ▼                                       │
│              ┌──────────────────────────┐                          │
│              │   Ingestion Service      │                          │
│              │   (Feed Handlers)        │                          │
│              └────────────┬─────────────┘                          │
│                           │                                        │
│                           ▼                                        │
│              ┌──────────────────────────┐                          │
│              │  Normalization Layer     │                          │
│              │  (Price/Symbol Mapping)  │                          │
│              └────────────┬─────────────┘                          │
│                           │                                        │
│                           ▼                                        │
│              ┌──────────────────────────┐                          │
│              │     Kafka Topics         │                          │
│              │  orion.market-data.raw   │                          │
│              │  orion.market-data.norm  │                          │
│              └────────────┬─────────────┘                          │
│                           │                                        │
│         ┌─────────────────┼─────────────────┐                      │
│         ▼                 ▼                 ▼                      │
│  ┌────────────┐    ┌────────────┐    ┌────────────┐               │
│  │ Aggregation│    │ Historical │    │ Conflation │               │
│  │   Engine   │    │  Service   │    │   Engine   │               │
│  └─────┬──────┘    └─────┬──────┘    └─────┬──────┘               │
│        │                 │                 │                       │
│        ▼                 ▼                 ▼                       │
│  ┌─────────────────────────────────────────────────┐              │
│  │           Distribution Layer                    │              │
│  │   (WebSocket / SSE / gRPC Streaming)           │              │
│  └─────────────────────────────────────────────────┘              │
│                           │                                        │
│                           ▼                                        │
│  ┌─────────────────────────────────────────────────┐              │
│  │              Client Applications                │              │
│  │    (Workstation UI, Trading Algorithms)        │              │
│  └─────────────────────────────────────────────────┘              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Data Model

### Market Data Event Types

```typescript
// Price Quote
interface PriceQuote {
  symbol: string;           // Normalized symbol (e.g., EUR/USD)
  source: string;           // LP identifier
  bidPrice: number;
  bidSize: number;
  askPrice: number;
  askSize: number;
  midPrice: number;
  spread: number;
  timestamp: number;        // Unix ms
  sequenceNumber: number;
}

// Aggregated Best Price
interface BestPrice {
  symbol: string;
  bestBid: { price: number; size: number; source: string };
  bestAsk: { price: number; size: number; source: string };
  vwap: number;             // Volume-weighted average
  sources: string[];        // Contributing LPs
  timestamp: number;
}

// OHLC Bar
interface OHLCBar {
  symbol: string;
  interval: '1m' | '5m' | '15m' | '1h' | '1d';
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  vwap: number;
  timestamp: number;
}
```

## Technical Considerations

### Performance Requirements
- **Latency**: < 5ms from LP to client
- **Throughput**: 100,000+ ticks/second
- **Conflation**: Configurable per client (10ms - 1000ms)

### Kafka Topics
- `orion.market-data.raw` - Raw LP prices
- `orion.market-data.normalized` - Normalized prices
- `orion.market-data.aggregated` - Best prices
- `orion.market-data.ohlc` - OHLC bars

### Storage
- **Hot**: Redis for current prices
- **Warm**: TimescaleDB for historical (30 days)
- **Cold**: S3 Parquet for long-term archive

## Dependencies

- Epic 05: Event Bus Infrastructure
- Epic 04: Reference Data (Instruments)

## Success Metrics

| Metric | Target |
|--------|--------|
| End-to-end latency | < 5ms p99 |
| Data freshness | < 100ms stale |
| Throughput | 100k ticks/sec |
| Availability | 99.99% |
