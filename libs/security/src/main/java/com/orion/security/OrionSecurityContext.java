package com.orion.security;

import java.util.List;

/**
 * Full security context carrying authentication, authorization, and tenant info.
 *
 * <p>WHY a record: immutable, thread-safe, travels with every request. This is the "single source
 * of truth" for all security checks within a request.
 *
 * @param user authenticated user information
 * @param tenant tenant context for isolation
 * @param roles user's platform roles
 * @param entitlements user's ABAC entitlements
 * @param token original bearer token (for forwarding to downstream services)
 * @param correlationId trace correlation ID for this request
 */
public record OrionSecurityContext(
        AuthenticatedUser user,
        TenantContext tenant,
        List<Role> roles,
        Entitlements entitlements,
        String token,
        String correlationId) {}
