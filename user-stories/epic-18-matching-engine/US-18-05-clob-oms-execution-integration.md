# US-18-05: OMS & Execution Service Integration

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-18-05 |
| **Epic** | EPIC-18: Matching Engine (CLOB) |
| **Title** | OMS & Execution Service Integration |
| **Priority** | P2 |
| **Story Points** | 5 |

## User Story

**As a** system integrator  
**I want** the CLOB to integrate cleanly with the existing OMS and execution service  
**So that** orders routed to the CLOB behave like any other order source in the platform.

## Acceptance Criteria

### AC1: OMS → CLOB Routing
- **Given** OMS orders for CLOB-enabled instruments
- **When** routing rules direct an order to the matching engine
- **Then** the order is transformed into a `ClobOrder` and submitted to the CLOB, and OMS transitions to `WORKING` when accepted.

### AC2: Fills → OMS & Execution
- **Given** matches produced by the CLOB
- **When** trades are generated (`MatchEvent`)
- **Then** corresponding fills are reported back to OMS and `TradeExecuted` events are sent to the Execution Service.

### AC3: State Consistency
- **Given** a series of partial fills and final completion
- **When** the CLOB, OMS, and Execution Service states are inspected
- **Then** the order status, filled quantity, and trade records are consistent across all three.

## Technical Specification

- Extend OMS routing logic to support `destination = CLOB` for specific instruments.
- Add an adapter layer between OMS order DTOs and `ClobOrder` model.
- Use existing event catalog for fill and trade events; CLOB should be largely transparent from a consumer perspective.

## Definition of Done

- [ ] OMS can route orders to CLOB based on instrument configuration.
- [ ] Integration tests verify E2E flow: OMS order → CLOB match → OMS fill → TradeExecuted.
- [ ] Documentation updated with routing rules and operational considerations.
