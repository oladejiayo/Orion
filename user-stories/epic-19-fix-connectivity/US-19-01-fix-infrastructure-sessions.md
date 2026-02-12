# US-19-01: FIX Infrastructure & Session Management

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-19-01 |
| **Epic** | EPIC-19: FIX Connectivity & Simulated Counterparties |
| **Title** | FIX Infrastructure & Session Management |
| **Priority** | P2 |
| **Story Points** | 8 |

## User Story

**As a** platform engineer  
**I want** a basic FIX engine and session management layer  
**So that** I can establish, monitor, and recover FIX sessions with simulated counterparties.

## Acceptance Criteria

- FIX engine configured (e.g., QuickFIX/J or equivalent) with:
  - session settings (sender/target comp IDs, heartbeats, time zones)
  - logon/logoff flows
  - heartbeat, test request, resend request handling
- Ability to run as **acceptor** or **initiator** (configurable per profile).
- Sessions exposed via metrics and logs (connectivity status, sequence numbers).

## Technical Specification

- Add a `fix-gateway` service with:
  - configuration files under `config/fix/`
  - integration with existing logging & metrics stack.
- Implement a minimal admin console endpoint to list sessions and force logout.

## Definition of Done

- [ ] FIX engine service builds and runs locally (Docker).
- [ ] One test session connects using a standard FIX client (e.g., simulator).
- [ ] Metrics and logs show healthy heartbeat and sequence number management.
