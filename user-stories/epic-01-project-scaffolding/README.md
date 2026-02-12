# Epic 01: Project Scaffolding & Foundation Setup

## Epic Overview

**Epic ID:** EPIC-01  
**Epic Name:** Project Scaffolding & Foundation Setup  
**Priority:** P0 - Critical (Must be completed first)  
**Target Milestone:** M0 — Foundations  
**Estimated Effort:** 2-3 Sprints

## Business Context

Before any feature development can begin, the Orion platform requires a solid foundation of project structure, development tooling, shared libraries, and local development environment. This epic establishes the monorepo structure, CI/CD pipelines, containerization strategy, and the essential shared libraries that all subsequent services will depend upon.

## Epic Goals

1. **Establish Monorepo Structure:** Create a well-organized repository structure following the recommended layout from the PRD
2. **Setup Development Environment:** Configure local development with Docker Compose for all dependencies (Kafka, PostgreSQL, Redis)
3. **Create Shared Libraries:** Build foundational libraries for events, security, observability, and common utilities
4. **Configure CI/CD Pipeline:** Establish GitHub Actions workflows for build, test, and deployment
5. **Define Coding Standards:** Set up linters, formatters, and code quality tools
6. **Create Proto Definitions:** Set up Protobuf definitions and code generation for gRPC services

## Success Criteria

- [ ] All developers can clone repo and run `docker-compose up` to start full local environment
- [ ] CI pipeline passes with basic smoke tests
- [ ] Shared libraries are published and consumable by services
- [ ] Proto files compile and generate stubs for all supported languages
- [ ] Documentation is complete for onboarding new developers
- [ ] Code quality gates are enforced (linting, formatting, coverage thresholds)

## Dependencies

- None (this is the foundational epic)

## Downstream Dependencies

All other epics depend on this one being completed first.

## Technical Scope

### Technology Stack
- **Languages:** TypeScript (Node.js for services), React (Frontend)
- **Build Tool:** Nx or Turborepo for monorepo management
- **Containerization:** Docker, Docker Compose
- **Message Broker:** Apache Kafka (Redpanda for local dev)
- **Database:** PostgreSQL 15+
- **Cache:** Redis 7+
- **gRPC:** Protocol Buffers v3, grpc-js
- **API:** REST with Express.js, WebSocket for streaming

### Repository Structure to Create
```
/docs
  /prd
  /architecture
  /runbooks
/services
  /bff-workstation
  /bff-admin
  /marketdata-ingest
  /marketdata-projection
  /rfq-service
  /lp-bot-service
  /execution-service
  /posttrade-service
  /analytics-service
  /admin-service
  /notification-service
/libs
  /event-model
  /security
  /observability
  /common
/proto
  /v1
/schemas
  /v1
/infra
  /terraform
  /docker-compose
/benchmarks
/scripts
/web
  /workstation-ui
  /admin-ui
```

## User Stories in This Epic

| Story ID | Story Title | Priority | Points |
|----------|-------------|----------|--------|
| US-01-01 | Initialize Monorepo with Build Tool | P0 | 5 |
| US-01-02 | Create Docker Compose Local Development Environment | P0 | 8 |
| US-01-03 | Setup Shared Event Model Library | P0 | 8 |
| US-01-04 | Setup Shared Security Library | P0 | 5 |
| US-01-05 | Setup Shared Observability Library | P0 | 5 |
| US-01-06 | Setup Protobuf Definitions and Code Generation | P0 | 8 |
| US-01-07 | Configure GitHub Actions CI Pipeline | P0 | 5 |
| US-01-08 | Setup Code Quality Tools and Standards | P1 | 3 |
| US-01-09 | Create Base Service Template | P0 | 5 |
| US-01-10 | Setup Database Migration Framework | P0 | 5 |
| US-01-11 | Create Development Documentation | P1 | 3 |
| US-01-12 | Setup Environment Configuration Management | P0 | 3 |

## Risks & Mitigations

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Monorepo tool complexity | Medium | High | Start with simpler Nx configuration, add complexity incrementally |
| Docker environment resource usage | Medium | Medium | Document minimum system requirements, provide lightweight dev mode |
| Protobuf schema design mistakes | Medium | High | Follow established patterns, conduct design review before implementation |

## Acceptance Criteria

1. Repository structure matches PRD specification
2. `npm install` or equivalent works from repo root
3. `docker-compose up` starts all infrastructure services
4. At least one service template compiles and runs
5. CI pipeline executes and reports status
6. All shared libraries have 80%+ test coverage
7. Developer documentation enables new team member onboarding in < 1 day

## Notes

- This epic should be completed by a senior engineer or tech lead
- All decisions made should be documented as Architecture Decision Records (ADRs)
- Consider future scalability when making technology choices
