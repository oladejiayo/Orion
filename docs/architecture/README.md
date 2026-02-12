# Architecture Documentation

> System architecture, component diagrams, communication patterns, and design documentation for the Orion platform.

## Contents

| Document | Description |
|----------|-------------|
| [overview.md](overview.md) | System overview, technology stack, Maven module structure, design principles |
| [services.md](services.md) | Shared libraries (4 built), domain services (8 planned), infrastructure services |
| [communication.md](communication.md) | gRPC, Kafka, REST, WebSocket protocols — patterns, topics, security |
| [diagrams/](diagrams/) | Mermaid architecture diagrams — C4 context, state machines, data flows, deployment |

## Related Documentation

- [API Contracts](../api/) — gRPC service reference, REST conventions, event catalog
- [ADRs](../adr/) — Architecture Decision Records (001–005)
- [ELI5 Guide](../eli5-guide.md) — Beginner-friendly explanations of what we've built
- [Knowledge Base](../knowledge.md) — Living knowledge base with decisions and patterns

*Last updated after US-01-06*
