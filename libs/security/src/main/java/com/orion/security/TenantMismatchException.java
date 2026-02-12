package com.orion.security;

/**
 * Thrown when a request attempts to access a resource belonging to a different tenant.
 * <p>
 * WHY a RuntimeException: tenant mismatch is a programming/security error,
 * not a recoverable condition. Fail fast and loud.
 */
public class TenantMismatchException extends RuntimeException {

    private final String expectedTenantId;
    private final String actualTenantId;

    public TenantMismatchException(String expectedTenantId, String actualTenantId) {
        super("Tenant mismatch: context tenant '%s' cannot access resource of tenant '%s'"
                .formatted(expectedTenantId, actualTenantId));
        this.expectedTenantId = expectedTenantId;
        this.actualTenantId = actualTenantId;
    }

    public String expectedTenantId() {
        return expectedTenantId;
    }

    public String actualTenantId() {
        return actualTenantId;
    }
}
