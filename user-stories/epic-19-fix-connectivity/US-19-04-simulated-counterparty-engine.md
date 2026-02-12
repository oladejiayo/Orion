# US-19-04: Simulated Counterparty Engine

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-19-04 |
| **Epic** | EPIC-19: FIX Connectivity & Simulated Counterparties |
| **Title** | Simulated Counterparty Engine |
| **Priority** | P2 |
| **Story Points** | 5 |

## User Story

**As a** demo engineer  
**I want** simulated counterparties that respond to FIX RFQs and orders with configurable behavior  
**So that** we can showcase FIX workflows without connecting to real venues.

## Acceptance Criteria

- Simulation engine supports:
  - deterministic price responses based on market data
  - configurable latency distributions
  - basic rejection and cancel scenarios.
- Configurable via YAML/JSON (tickers, spreads, behavior profiles).

## Definition of Done

- [ ] Simulated counterparty library implemented and plugged into FIX gateway.
- [ ] Example config profiles checked into `config/fix/simulations/`.
- [ ] Demo script documented for running a full simulated FIX session.
