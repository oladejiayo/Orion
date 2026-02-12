package com.orion.security;

/**
 * Tenant tiers for multi-tenant isolation.
 * <p>
 * WHY an enum: tenants are categorized into fixed tiers that determine
 * feature availability and resource limits.
 */
public enum TenantType {

    STANDARD("standard"),
    PREMIUM("premium"),
    ENTERPRISE("enterprise");

    private final String value;

    TenantType(String value) {
        this.value = value;
    }

    /** The canonical string representation. */
    public String value() {
        return value;
    }
}
