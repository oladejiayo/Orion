package com.orion.observability;

/**
 * Health status for an individual component or the aggregate system.
 */
public enum HealthStatus {

    /** All components are functioning normally. */
    HEALTHY,

    /** Some components are impaired but the system can still serve requests. */
    DEGRADED,

    /** One or more critical components are down; the system cannot serve requests. */
    UNHEALTHY
}
