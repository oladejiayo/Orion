package com.orion.observability;

/**
 * Immutable correlation context that flows through a request across services.
 * <p>
 * Every incoming request (HTTP, gRPC, Kafka) should establish a {@code CorrelationContext}
 * containing identifiers that enable distributed tracing and log correlation. These values
 * are propagated to downstream services via headers/metadata and injected into SLF4J MDC
 * for automatic inclusion in log output.
 *
 * @param correlationId unique ID for the business flow (e.g., an RFQ → quote → trade chain)
 * @param tenantId      tenant identifier for multi-tenant isolation and metric segmentation
 * @param userId        authenticated user performing the action (nullable for system events)
 * @param requestId     unique ID for this specific request (one correlation may span multiple requests)
 * @param spanId        current OpenTelemetry span ID (nullable if tracing is not active)
 * @param traceId       current OpenTelemetry trace ID (nullable if tracing is not active)
 */
public record CorrelationContext(
        String correlationId,
        String tenantId,
        String userId,
        String requestId,
        String spanId,
        String traceId
) {

    /**
     * MDC key for correlation ID.
     */
    public static final String MDC_CORRELATION_ID = "correlationId";

    /**
     * MDC key for tenant ID.
     */
    public static final String MDC_TENANT_ID = "tenantId";

    /**
     * MDC key for user ID.
     */
    public static final String MDC_USER_ID = "userId";

    /**
     * MDC key for request ID.
     */
    public static final String MDC_REQUEST_ID = "requestId";

    /**
     * MDC key for span ID.
     */
    public static final String MDC_SPAN_ID = "spanId";

    /**
     * MDC key for trace ID.
     */
    public static final String MDC_TRACE_ID = "traceId";

    /**
     * Compact constructor — ensures correlationId is never null.
     */
    public CorrelationContext {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be null or blank");
        }
    }
}
