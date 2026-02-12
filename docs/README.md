# Orion — Documentation

> All project documentation for the Orion Liquidity & Data Platform.

## Structure

```
docs/
├── architecture/           # System architecture and design
│   ├── overview.md         #   System overview, tech stack, module structure
│   ├── services.md         #   4 built libraries + 8 planned services
│   ├── communication.md    #   gRPC, Kafka, REST, WebSocket patterns
│   └── diagrams/           #   Mermaid architecture diagrams (9 diagrams)
├── api/                    # API contracts and conventions
│   ├── grpc-services.md    #   5 gRPC services, 20 RPCs reference
│   ├── events.md           #   30+ event types, Kafka topics
│   └── rest-conventions.md #   REST endpoints, error format, WebSocket protocol
├── runbooks/               # Operational guides
│   ├── local-development.md #  Prerequisites, build, Docker Compose, IDE
│   ├── debugging.md         #  Troubleshooting, tracing, common errors
│   └── deployment.md        #  Environments, CI/CD, AWS, scaling
├── adr/                    # Architecture Decision Records
│   ├── 001-monorepo-maven-multi-module.md
│   ├── 002-shared-event-model.md
│   ├── 003-security-library-design.md
│   ├── 004-observability-library-design.md
│   └── 005-grpc-protobuf-contracts.md
├── prd/                    # Product Requirements Documents
├── knowledge.md            # Living knowledge base (all 6 stories)
└── eli5-guide.md           # Explain Like I'm 5 guide
```

## Quick Links

| Category | Documents |
|----------|-----------|
| **Architecture** | [Overview](architecture/overview.md) · [Services](architecture/services.md) · [Communication](architecture/communication.md) · [Diagrams](architecture/diagrams/) |
| **API** | [gRPC Services](api/grpc-services.md) · [Events](api/events.md) · [REST Conventions](api/rest-conventions.md) |
| **Runbooks** | [Local Dev](runbooks/local-development.md) · [Debugging](runbooks/debugging.md) · [Deployment](runbooks/deployment.md) |
| **Decisions** | [ADR-001](adr/001-monorepo-maven-multi-module.md) · [ADR-002](adr/002-shared-event-model.md) · [ADR-003](adr/003-security-library-design.md) · [ADR-004](adr/004-observability-library-design.md) · [ADR-005](adr/005-grpc-protobuf-contracts.md) |
| **Guides** | [ELI5 Guide](eli5-guide.md) · [Knowledge Base](knowledge.md) |

*Last updated after US-01-06 — 390 tests passing*
