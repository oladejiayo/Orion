package com.orion.observability;

/**
 * Health result for a single component.
 *
 * @param name component name (e.g., "postgres", "redis", "kafka")
 * @param status health status of this component
 * @param message optional human-readable message (e.g., error details)
 * @param latencyMs time taken to check this component (in milliseconds)
 */
public record ComponentHealth(String name, HealthStatus status, String message, long latencyMs) {

    /** Creates a healthy component result. */
    public static ComponentHealth healthy(String name, long latencyMs) {
        return new ComponentHealth(name, HealthStatus.HEALTHY, null, latencyMs);
    }

    /** Creates an unhealthy component result. */
    public static ComponentHealth unhealthy(String name, String message, long latencyMs) {
        return new ComponentHealth(name, HealthStatus.UNHEALTHY, message, latencyMs);
    }
}
