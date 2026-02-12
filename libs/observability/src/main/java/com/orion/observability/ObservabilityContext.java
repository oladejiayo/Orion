package com.orion.observability;

/**
 * Aggregates service-level metadata with the current correlation context.
 * <p>
 * Used to initialize MDC at the start of a request, combining static service
 * information (name, version, environment) with per-request correlation data.
 *
 * @param serviceName  logical name of the service (e.g., "rfq-service")
 * @param serviceVersion  version string (e.g., "1.0.0")
 * @param environment  deployment environment (e.g., "production", "staging", "local")
 * @param correlation  per-request correlation context
 */
public record ObservabilityContext(
        String serviceName,
        String serviceVersion,
        String environment,
        CorrelationContext correlation
) {

    /**
     * MDC key for service name.
     */
    public static final String MDC_SERVICE_NAME = "serviceName";

    /**
     * MDC key for service version.
     */
    public static final String MDC_SERVICE_VERSION = "serviceVersion";

    /**
     * MDC key for environment.
     */
    public static final String MDC_ENVIRONMENT = "environment";

    public ObservabilityContext {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be null or blank");
        }
        if (correlation == null) {
            throw new IllegalArgumentException("correlation must not be null");
        }
    }
}
