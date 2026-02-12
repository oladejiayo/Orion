# US-19-02: FIX ↔ RFQ Workflow Mapping

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-19-02 |
| **Epic** | EPIC-19: FIX Connectivity & Simulated Counterparties |
| **Title** | FIX ↔ RFQ Workflow Mapping |
| **Priority** | P2 |
| **Story Points** | 5 |

## User Story

**As a** trading integration engineer  
**I want** to map basic RFQ workflows between FIX messages and internal RFQ service commands  
**So that** we can demonstrate FIX-based RFQ flows end-to-end.

## Acceptance Criteria

- Mapping defined for a minimal RFQ flow (for example using custom tags or standard messages, depending on chosen profile), covering:
  - RFQ request
  - Quote response
  - Quote acceptance/cancellation
- Internal events (`RFQCreated`, `QuoteReceived`, `QuoteAccepted`) triggered from FIX messages.
- Responses sent back via FIX (e.g., quote messages, execution reports or custom confirmations).

## Definition of Done

- [ ] RFQ FIX mapping documented (message types, tags, examples).
- [ ] Integration tests: FIX client → FIX gateway → RFQ service → FIX responses.
