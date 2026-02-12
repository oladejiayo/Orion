package com.orion.observability;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Registry that aggregates multiple {@link HealthCheck} instances and runs them
 * concurrently to produce a single {@link HealthResult}.
 * <p>
 * Health checks are registered by component name. When {@link #checkAll()} is called,
 * all checks run in parallel (via {@link CompletableFuture}). A timeout prevents slow
 * checks from blocking the health endpoint indefinitely.
 */
public final class HealthCheckRegistry {

    /** Default timeout for individual health checks (5 seconds). */
    public static final long DEFAULT_TIMEOUT_MS = 5000;

    private final Map<String, HealthCheck> checks = new ConcurrentHashMap<>();
    private final long timeoutMs;

    /**
     * Creates a registry with the default timeout.
     */
    public HealthCheckRegistry() {
        this(DEFAULT_TIMEOUT_MS);
    }

    /**
     * Creates a registry with a custom timeout.
     *
     * @param timeoutMs timeout in milliseconds for each individual health check
     */
    public HealthCheckRegistry(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        this.timeoutMs = timeoutMs;
    }

    /**
     * Registers a health check under the given component name.
     * Replaces any existing check for the same name.
     *
     * @param name  component name (e.g., "postgres", "redis")
     * @param check the health check to register
     */
    public void register(String name, HealthCheck check) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (check == null) {
            throw new IllegalArgumentException("check must not be null");
        }
        checks.put(name, check);
    }

    /**
     * Removes a health check by component name.
     *
     * @param name component name to deregister
     * @return true if a check was removed
     */
    public boolean deregister(String name) {
        return checks.remove(name) != null;
    }

    /**
     * Runs all registered health checks concurrently and aggregates the results.
     * <p>
     * Returns immediately with {@link HealthStatus#HEALTHY} if no checks are registered.
     * Individual checks that exceed the timeout are reported as {@link HealthStatus#UNHEALTHY}.
     *
     * @return the aggregate health result
     */
    public HealthResult checkAll() {
        if (checks.isEmpty()) {
            return new HealthResult(HealthStatus.HEALTHY, Map.of(), Instant.now());
        }

        Map<String, ComponentHealth> results = new LinkedHashMap<>();
        HealthStatus overall = HealthStatus.HEALTHY;

        // Run all checks concurrently
        Map<String, CompletableFuture<ComponentHealth>> futures = new LinkedHashMap<>();
        for (Map.Entry<String, HealthCheck> entry : checks.entrySet()) {
            futures.put(entry.getKey(), entry.getValue().check());
        }

        // Collect results with timeout
        for (Map.Entry<String, CompletableFuture<ComponentHealth>> entry : futures.entrySet()) {
            String name = entry.getKey();
            try {
                ComponentHealth result = entry.getValue()
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .join();
                results.put(name, result);

                if (result.status() == HealthStatus.UNHEALTHY) {
                    overall = HealthStatus.UNHEALTHY;
                } else if (result.status() == HealthStatus.DEGRADED && overall == HealthStatus.HEALTHY) {
                    overall = HealthStatus.DEGRADED;
                }
            } catch (Exception e) {
                results.put(name, ComponentHealth.unhealthy(name, "Timeout or error: " + e.getMessage(), timeoutMs));
                overall = HealthStatus.UNHEALTHY;
            }
        }

        return new HealthResult(overall, results, Instant.now());
    }

    /**
     * Returns the number of registered health checks.
     */
    public int size() {
        return checks.size();
    }

    /**
     * Returns the configured timeout in milliseconds.
     */
    public long timeoutMs() {
        return timeoutMs;
    }
}
