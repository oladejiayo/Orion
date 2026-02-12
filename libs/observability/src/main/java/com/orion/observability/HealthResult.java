package com.orion.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Aggregate health result from all registered health checks.
 *
 * @param status    overall system health (HEALTHY if all checks pass, UNHEALTHY if any fail)
 * @param checks    individual component health results keyed by component name
 * @param timestamp when the health check was performed (ISO 8601)
 */
public record HealthResult(
        HealthStatus status,
        Map<String, ComponentHealth> checks,
        Instant timestamp
) {

    public HealthResult {
        checks = Map.copyOf(checks);
    }
}
