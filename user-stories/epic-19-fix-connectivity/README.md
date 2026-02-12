# Epic 19: FIX Connectivity & Simulated Counterparties

## Epic Overview

| Field | Value |
|-------|-------|
| **Epic ID** | EPIC-19 |
| **Epic Name** | FIX Connectivity & Simulated Counterparties |
| **Priority** | P2 – Stretch (V2) |
| **Target Release** | V2 (Post-MVP) |
| **Estimated Effort** | 3–4 sprints |
| **PRD Reference** | §6.3 V2 Scope (FIX adapter), §7.2.1, §19.4 |

## Epic Description

Implement a minimal FIX connectivity layer and simulated counterparties (LPs/venues) to demonstrate integration with industry-standard FIX-based workflows. This includes a FIX acceptor for incoming client sessions (optional) and/or initiator sessions to simulated venues, with mapping to internal RFQ and OMS flows.

## Goals

1. **FIX Session Management**: Manage FIX sessions, logon/logoff, heartbeats, sequence numbers.
2. **Message Mapping**: Map selected FIX flows (e.g., RFQ, NewOrderSingle, ExecutionReport) to internal commands/events.
3. **Simulation Harness**: Provide a deterministic, safe environment for demonstrating FIX-based interactions.
4. **Certification Style Tests**: Basic automated tests that resemble certification scripts.

## Non-Goals

- Not a full multi-venue, production-grade FIX hub.
- Not implementing every FIX message type; focus on a minimal, representative subset.

## User Stories

| Story ID | Title | Priority | Points |
|----------|-------|----------|--------|
| US-19-01 | FIX Infrastructure & Session Management | P2 | 8 |
| US-19-02 | FIX ↔ RFQ Workflow Mapping | P2 | 5 |
| US-19-03 | FIX ↔ OMS Order Workflow Mapping | P2 | 5 |
| US-19-04 | Simulated Counterparty Engine | P2 | 5 |
| US-19-05 | FIX Certification Test Suite | P2 | 5 |

## Dependencies

- **Epic 07 – RFQ Workflow**
- **Epic 09 – OMS Orders V1**
- **Epic 05 – Event Bus Infrastructure**

## Success Criteria

- [ ] At least one end-to-end FIX RFQ or order workflow demonstrated using simulated counterparties.
- [ ] FIX sessions stable under basic error scenarios (disconnects, resend requests).
- [ ] Automated tests validate core FIX flows and mappings.
