package com.orion.observability;

import java.util.concurrent.CompletableFuture;

/**
 * Functional interface for a single health check component.
 * <p>
 * Implementations perform a lightweight probe of a dependency (database, cache,
 * message broker) and return the result asynchronously. The health check registry
 * aggregates multiple checks concurrently.
 * <p>
 * Example usage:
 * <pre>{@code
 * HealthCheck postgresCheck = () -> {
 *     long start = System.currentTimeMillis();
 *     try {
 *         dataSource.getConnection().isValid(2);
 *         return CompletableFuture.completedFuture(
 *             ComponentHealth.healthy("postgres", System.currentTimeMillis() - start));
 *     } catch (Exception e) {
 *         return CompletableFuture.completedFuture(
 *             ComponentHealth.unhealthy("postgres", e.getMessage(), System.currentTimeMillis() - start));
 *     }
 * };
 * }</pre>
 */
@FunctionalInterface
public interface HealthCheck {

    /**
     * Performs a health check and returns the result asynchronously.
     *
     * @return a future that completes with the component health result
     */
    CompletableFuture<ComponentHealth> check();
}
