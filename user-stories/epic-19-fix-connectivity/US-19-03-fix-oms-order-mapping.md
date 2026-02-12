# US-19-03: FIX ↔ OMS Order Workflow Mapping

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-19-03 |
| **Epic** | EPIC-19: FIX Connectivity & Simulated Counterparties |
| **Title** | FIX ↔ OMS Order Workflow Mapping |
| **Priority** | P2 |
| **Story Points** | 5 |

## User Story

**As a** trading integration engineer  
**I want** to map FIX order flows (e.g., `NewOrderSingle`, `OrderCancelRequest`) to the internal OMS commands  
**So that** we can demonstrate order entry, cancel, and status updates over FIX.

## Acceptance Criteria

- Mapping defined for:
  - `NewOrderSingle` → `PlaceOrder` command
  - `OrderCancelRequest` → `CancelOrder`
  - Execution reports back to FIX client reflecting OMS/Execution state.

## Definition of Done

- [ ] Order FIX mapping documented (fields, tags, examples).
- [ ] Integration tests: FIX client submits order, OMS routes, fills, and sends execution report back.
