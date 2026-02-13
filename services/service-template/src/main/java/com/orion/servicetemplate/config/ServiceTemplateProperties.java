package com.orion.servicetemplate.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for Orion services.
 *
 * <p>WHY: Replaces Zod schema validation from the TypeScript story. Spring Boot binds YAML/env
 * properties to this record at startup and validates them via Bean Validation. Invalid config →
 * fail-fast with a clear error message (no silent misconfiguration in production).
 *
 * <p>Properties are bound from the {@code orion.service.*} prefix:
 *
 * <pre>
 * orion:
 *   service:
 *     name: rfq-service
 *     environment: production
 *     description: RFQ workflow service
 *     grpc-port: 9090
 * </pre>
 *
 * @param name Service name used for logging, metrics, and tracing. Required.
 * @param environment Deployment environment (development, staging, production).
 * @param description Human-readable service description for /actuator/info.
 * @param grpcPort Port for the gRPC server (default 9090, 0 = disabled).
 */
@ConfigurationProperties(prefix = "orion.service")
@Validated
public record ServiceTemplateProperties(
        @NotBlank String name, String environment, String description, int grpcPort) {

    /**
     * Compact constructor — applies defaults for optional fields. Runs BEFORE Bean Validation, so
     * defaults satisfy constraints.
     */
    public ServiceTemplateProperties {
        if (environment == null || environment.isBlank()) {
            environment = "development";
        }
        if (grpcPort <= 0) {
            grpcPort = 9090;
        }
    }
}
