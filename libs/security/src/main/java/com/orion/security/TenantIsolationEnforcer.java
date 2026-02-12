package com.orion.security;

/**
 * Enforces tenant isolation by comparing the request's tenant context
 * against a resource's tenant ID.
 * <p>
 * WHY a utility class: every service that accesses tenant-scoped resources
 * must verify that the authenticated user belongs to the same tenant.
 * Fail-fast with {@link TenantMismatchException} prevents data leakage.
 */
public final class TenantIsolationEnforcer {

    private TenantIsolationEnforcer() {
        // utility class
    }

    /**
     * Verifies that the security context's tenant matches the resource's tenant.
     *
     * @param context          the authenticated security context
     * @param resourceTenantId the tenant ID of the resource being accessed
     * @throws TenantMismatchException if the tenants do not match
     */
    public static void enforce(OrionSecurityContext context, String resourceTenantId) {
        String contextTenantId = context.tenant().tenantId();
        if (!contextTenantId.equals(resourceTenantId)) {
            throw new TenantMismatchException(contextTenantId, resourceTenantId);
        }
    }
}
