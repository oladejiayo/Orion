# US-19-05: FIX Certification Test Suite

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-19-05 |
| **Epic** | EPIC-19: FIX Connectivity & Simulated Counterparties |
| **Title** | FIX Certification Test Suite |
| **Priority** | P2 |
| **Story Points** | 5 |
| **Status** | Todo |

## User Story

**As a** QA engineer  
**I want** an automated test suite that exercises the core FIX workflows  
**So that** we can detect regressions and demonstrate correctness similar to an exchange certification.

## Acceptance Criteria

- Test harness (e.g., using a FIX simulator or scripts) that:
  - runs a logon/logoff/heartbeat scenario
  - executes basic RFQ and order flows
  - validates tag values and state transitions.
- CI job added to run a subset of FIX tests on demand.

## Definition of Done

- [ ] FIX certification tests implemented and documented.
- [ ] Test reports published in CI.
