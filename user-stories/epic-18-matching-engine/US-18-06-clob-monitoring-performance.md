# US-18-06: CLOB Monitoring & Performance Benchmarks

## Story Information

| Field | Value |
|-------|-------|
| **Story ID** | US-18-06 |
| **Epic** | EPIC-18: Matching Engine (CLOB) |
| **Title** | CLOB Monitoring & Performance Benchmarks |
| **Priority** | P2 |
| **Story Points** | 5 |

## User Story

**As a** platform engineer  
**I want** metrics, dashboards, and performance benchmarks for the CLOB  
**So that** I can verify matching throughput/latency and detect issues early.

## Acceptance Criteria

- **Metrics**: Expose Prometheus metrics for:
  - matches per second
  - average and p95/p99 match latency
  - order queue depth per instrument
  - rejected orders by reason
- **Dashboards**: Grafana dashboard panels added under the existing trading SLO dashboards.
- **Benchmarks**: A repeatable benchmark scenario (linked to Epic 17 performance tests) demonstrating target throughput and latency.

## Definition of Done

- [ ] CLOB exposes Prometheus metrics integrated with Epic 16 stack.
- [ ] Grafana dashboard created for CLOB health and throughput.
- [ ] Benchmark results documented under `benchmarks/clob-performance.md` with configuration and results.
