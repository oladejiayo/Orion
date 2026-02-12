# US-12-08: Searchable Blotters with OpenSearch

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-12-08 |
| **Epic** | EPIC-12: Analytics & Data Products |
| **Title** | Searchable Blotters with OpenSearch |
| **Priority** | Medium |
| **Story Points** | 8 |

## User Story

**As a** trader or operations analyst  
**I want** fast, flexible search over orders, trades, and RFQs  
**So that** I can quickly locate and investigate specific activity without scanning large tables.

## Acceptance Criteria

### AC1: Index Design
- **Given** order, trade, and RFQ entities
- **When** they are indexed into OpenSearch
- **Then** each has an index with fields optimized for search and filter (IDs, symbols, dates, statuses, counterparties, correlation IDs).

### AC2: Ingestion Pipeline
- **Given** events `OrderPlaced`, `OrderFilled`, `TradeExecuted`, `RFQCreated`, `QuoteReceived`
- **When** they are emitted on Kafka
- **Then** an analytics/indexer service consumes them and maintains denormalized OpenSearch documents for blotters.

### AC3: Query API
- **Given** the BFF or analytics API
- **When** a client issues search requests with filters (date ranges, instrument, status, client, correlationId)
- **Then** results are returned within **< 1s** for typical query sizes and support pagination.

## Technical Specification

- Introduce an `analytics-indexer` service that consumes relevant topics and updates OpenSearch indices.
- Define index templates and mappings (including analyzers for free-text fields where applicable).
- Implement REST/gRPC query endpoints used by the Workstation UI blotters.

## Definition of Done

- [ ] OpenSearch indices for orders, trades, RFQs created with mappings.
- [ ] Indexer service deployed and consuming events.
- [ ] API layer exposes search endpoints integrated with workstation blotter queries.
- [ ] Performance and relevance validated with sample datasets.
