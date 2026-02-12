# Shared Libraries

Java libraries shared across all microservices. Each subdirectory is a Maven module producing a plain JAR (not a Spring Boot application).

## Planned Libraries

| Library | Artifact ID | Description |
|---------|-------------|-------------|
| `event-model` | `orion-event-model` | Canonical event envelope, serialization, JSON Schema validation |
| `security` | `orion-security` | Auth helpers, JWT utilities, tenant enforcement |
| `observability` | `orion-observability` | OpenTelemetry, logging, metrics helpers |
| `common` | `orion-common` | Shared DTOs, exceptions, constants |

## Usage

Services declare library dependencies in their `pom.xml`:

```xml
<dependency>
    <groupId>com.orion</groupId>
    <artifactId>orion-event-model</artifactId>
</dependency>
```

Version is managed by the parent POM — no need to specify `<version>`.
