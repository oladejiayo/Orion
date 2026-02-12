package com.orion.observability.testing;

import com.orion.observability.CorrelationContext;

import java.util.UUID;

/**
 * Test factory for creating {@link CorrelationContext} instances with sensible defaults.
 * <p>
 * Placed in {@code src/main/java} so other modules can import it as a regular dependency
 * in their test scope. All values are deterministic for test reproducibility, but
 * convenience methods generate random UUIDs when needed.
 */
public final class TestCorrelationContextFactory {

    /** Default correlation ID for tests. */
    public static final String DEFAULT_CORRELATION_ID = "test-corr-001";

    /** Default tenant ID for tests. */
    public static final String DEFAULT_TENANT_ID = "tenant-test-001";

    /** Default user ID for tests. */
    public static final String DEFAULT_USER_ID = "user-test-001";

    /** Default request ID for tests. */
    public static final String DEFAULT_REQUEST_ID = "req-test-001";

    private TestCorrelationContextFactory() {
        // Utility class — no instantiation
    }

    /**
     * Creates a fully-populated correlation context with all default values.
     */
    public static CorrelationContext createDefault() {
        return new CorrelationContext(
                DEFAULT_CORRELATION_ID,
                DEFAULT_TENANT_ID,
                DEFAULT_USER_ID,
                DEFAULT_REQUEST_ID,
                null,
                null
        );
    }

    /**
     * Creates a correlation context with a random correlation ID and all other defaults.
     */
    public static CorrelationContext createRandom() {
        return new CorrelationContext(
                UUID.randomUUID().toString(),
                DEFAULT_TENANT_ID,
                DEFAULT_USER_ID,
                UUID.randomUUID().toString(),
                null,
                null
        );
    }

    /**
     * Creates a correlation context for a specific tenant.
     *
     * @param tenantId the tenant ID to use
     */
    public static CorrelationContext forTenant(String tenantId) {
        return new CorrelationContext(
                DEFAULT_CORRELATION_ID,
                tenantId,
                DEFAULT_USER_ID,
                DEFAULT_REQUEST_ID,
                null,
                null
        );
    }

    /**
     * Creates a correlation context for a specific user.
     *
     * @param userId the user ID to use
     */
    public static CorrelationContext forUser(String userId) {
        return new CorrelationContext(
                DEFAULT_CORRELATION_ID,
                DEFAULT_TENANT_ID,
                userId,
                DEFAULT_REQUEST_ID,
                null,
                null
        );
    }

    /**
     * Creates a fully customized correlation context.
     */
    public static CorrelationContext create(String correlationId, String tenantId,
                                           String userId, String requestId) {
        return new CorrelationContext(correlationId, tenantId, userId, requestId, null, null);
    }
}
