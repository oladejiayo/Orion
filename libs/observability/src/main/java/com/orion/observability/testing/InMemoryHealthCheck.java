package com.orion.observability.testing;

import com.orion.observability.ComponentHealth;
import com.orion.observability.HealthCheck;
import com.orion.observability.HealthStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A controllable health check for testing health aggregation logic.
 * <p>
 * Allows tests to programmatically set the health status and simulate failures
 * or slow checks. Placed in {@code src/main/java} for cross-module test use.
 */
public final class InMemoryHealthCheck implements HealthCheck {

    private final String componentName;
    private final AtomicReference<HealthStatus> status;
    private final AtomicReference<String> message;
    private final AtomicReference<Long> latencyMs;

    /**
     * Creates an InMemoryHealthCheck that starts as HEALTHY.
     *
     * @param componentName the component name reported in health results
     */
    public InMemoryHealthCheck(String componentName) {
        this.componentName = componentName;
        this.status = new AtomicReference<>(HealthStatus.HEALTHY);
        this.message = new AtomicReference<>(null);
        this.latencyMs = new AtomicReference<>(0L);
    }

    @Override
    public CompletableFuture<ComponentHealth> check() {
        return CompletableFuture.completedFuture(
                new ComponentHealth(componentName, status.get(), message.get(), latencyMs.get())
        );
    }

    /**
     * Sets this check to HEALTHY.
     */
    public InMemoryHealthCheck setHealthy() {
        this.status.set(HealthStatus.HEALTHY);
        this.message.set(null);
        return this;
    }

    /**
     * Sets this check to UNHEALTHY with the given message.
     */
    public InMemoryHealthCheck setUnhealthy(String message) {
        this.status.set(HealthStatus.UNHEALTHY);
        this.message.set(message);
        return this;
    }

    /**
     * Sets this check to DEGRADED with the given message.
     */
    public InMemoryHealthCheck setDegraded(String message) {
        this.status.set(HealthStatus.DEGRADED);
        this.message.set(message);
        return this;
    }

    /**
     * Sets the simulated latency for this check.
     */
    public InMemoryHealthCheck setLatencyMs(long latencyMs) {
        this.latencyMs.set(latencyMs);
        return this;
    }

    /**
     * Returns the component name.
     */
    public String componentName() {
        return componentName;
    }
}
