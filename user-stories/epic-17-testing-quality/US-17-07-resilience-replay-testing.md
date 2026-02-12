# US-17-07: Resilience & Replay Testing

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-17-07 |
| **Epic** | EPIC-17: Testing & Quality |
| **Title** | Resilience & Replay Testing |
| **Priority** | High (within Testing epic) |
| **Story Points** | 5 |

## User Story

**As a** platform SRE  
**I want** automated resilience and replay tests  
**So that** we can prove the system recovers correctly from failures and can rebuild read models from events as required by the PRD.

## Acceptance Criteria

### AC1: Chaos/Failure Scenarios
- **Given** a running staging environment
- **When** individual services (e.g., RFQ, Market Data Projection, CLOB) are killed under load
- **Then** they restart cleanly and:
  - no trades or orders are lost
  - in-flight requests either succeed or fail with clear, retryable responses
  - idempotent consumers do not duplicate effects.

### AC2: Read Model Replay
- **Given** event topics and current read models (RFQ blotter, trade blotter, order blotter, market snapshot)
- **When** we drop and rebuild the read model projections from Kafka
- **Then** the rebuilt state matches the prior state within an acceptable tolerance (e.g., row counts, checksums).

### AC3: Runbooks & Evidence
- **Given** the resilience tests
- **When** they are executed
- **Then** the results (logs, dashboards screenshots, or metrics) are stored under `benchmarks/` with clear instructions for reproducing.

## Technical Specification

- Implement automated chaos-style tests using scripts or a lightweight tool (e.g., `kubectl delete pod` loops or equivalent ECS restart scripts).
- Add a replay test harness for at least one read model (e.g., trade blotter) that:
  - snapshots existing state
  - drops the read model
  - rebuilds from Kafka events
  - compares row counts and sample checksums.

## Definition of Done

- [ ] Chaos scripts or jobs implemented for core services.
- [ ] Replay tests implemented for at least one major read model.
- [ ] Results documented under `benchmarks/resilience-report.md`.
