# Orion Service Template

> **Reference Spring Boot service** — copy this module when creating a new Orion microservice.

## What's Included

| Component | Description |
|-----------|-------------|
| `ServiceTemplateApplication` | Spring Boot entry point with graceful shutdown |
| `ServiceTemplateProperties` | Type-safe `@ConfigurationProperties` with Bean Validation |
| `CorrelationIdFilter` | HTTP filter — propagates/generates `X-Correlation-ID` |
| `GlobalExceptionHandler` | `@RestControllerAdvice` — RFC 7807 ProblemDetail errors |
| `GrpcCorrelationInterceptor` | gRPC `ServerInterceptor` — correlation ID from metadata |
| `GrpcExceptionInterceptor` | gRPC `ServerInterceptor` — exception → Status mapping |
| `WebConfig` | CORS configuration for local React development |
| `ServiceInfoController` | Example REST endpoint with constructor injection |
| Actuator | Health (`/actuator/health`), metrics (`/actuator/prometheus`) |
| Spring Profiles | `application.yml`, `application-local.yml`, `application-docker.yml` |

## Creating a New Service

```bash
# 1. Copy the template
cp -r services/service-template services/my-service

# 2. Rename packages
# com.orion.servicetemplate → com.orion.myservice

# 3. Update pom.xml
# <artifactId>orion-my-service</artifactId>
# <name>Orion :: Services :: My Service</name>

# 4. Update application.yml
# orion.service.name: my-service

# 5. Add to parent pom.xml
# <module>services/my-service</module>

# 6. Uncomment dependencies as needed (JPA, Kafka, gRPC starter)
```

## Running Locally

```bash
# Start infrastructure
docker compose up -d

# Run the service
./mvnw spring-boot:run -pl services/service-template -Dspring-boot.run.profiles=local

# Test endpoints
curl http://localhost:8080/api/v1/info
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus
```

## Package Structure (Clean Architecture)

```
com.orion.servicetemplate/
├── api/                    # REST controllers (inbound adapters)
│   └── ServiceInfoController.java
├── config/                 # Configuration (cross-cutting)
│   ├── ServiceTemplateProperties.java
│   └── WebConfig.java
├── domain/                 # Business logic (no framework deps)
│   └── (entities, events, services, ports)
├── infrastructure/         # Framework adapters (outbound)
│   ├── web/               # HTTP filters, error handling
│   │   ├── CorrelationIdFilter.java
│   │   └── GlobalExceptionHandler.java
│   └── grpc/              # gRPC interceptors
│       ├── GrpcCorrelationInterceptor.java
│       └── GrpcExceptionInterceptor.java
└── ServiceTemplateApplication.java
```

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `orion.service.name` | `service-template` | Service name for logs/metrics |
| `orion.service.environment` | `development` | Deployment environment |
| `orion.service.description` | (empty) | Human-readable description |
| `orion.service.grpc-port` | `9090` | gRPC server port (0 = disabled) |

## Adding Capabilities

### Database (JPA + PostgreSQL)

1. Uncomment `spring-boot-starter-data-jpa` and `postgresql` in `pom.xml`
2. Uncomment datasource config in `application-local.yml` and `application-docker.yml`
3. Add entities in `domain/entities/` and repositories in `infrastructure/persistence/`

### Kafka (Event Bus)

1. Uncomment `spring-kafka` in `pom.xml`
2. Uncomment Kafka config in `application-local.yml` and `application-docker.yml`
3. Add consumers in `infrastructure/kafka/` and publishers in `domain/events/`

### gRPC Server

1. Add `net.devh:grpc-server-spring-boot-starter` to `pom.xml`
2. Register interceptors as `@GrpcGlobalServerInterceptor` beans
3. Add gRPC service implementations in `infrastructure/grpc/`
