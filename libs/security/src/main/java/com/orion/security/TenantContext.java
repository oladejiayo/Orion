package com.orion.security;

/**
 * Tenant context for multi-tenant isolation.
 *
 * <p>WHY a record: immutable value object that travels with every request. Every resource access is
 * scoped to a single tenant.
 *
 * @param tenantId unique tenant identifier (from JWT 'tenant_id' claim)
 * @param tenantName optional human-readable tenant name
 * @param tenantType the tier of the tenant (determines feature limits)
 */
public record TenantContext(String tenantId, String tenantName, TenantType tenantType) {}
