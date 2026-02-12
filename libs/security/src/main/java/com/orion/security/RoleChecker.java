package com.orion.security;

import java.util.List;

/**
 * Role-based access control (RBAC) checker with hierarchy support.
 * <p>
 * WHY a utility class: RBAC logic is the same everywhere — check if the user's
 * roles (including inherited roles from the hierarchy) satisfy the requirement.
 * Centralising avoids each service re-implementing hierarchy logic.
 */
public final class RoleChecker {

    private RoleChecker() {
        // utility class
    }

    /**
     * Checks if the security context has the required role (directly or via hierarchy).
     * <p>
     * Example: if user has ADMIN, {@code hasRole(ctx, TRADER)} returns true
     * because ADMIN implies TRADER.
     */
    public static boolean hasRole(OrionSecurityContext context, Role required) {
        return context.roles().stream()
                .anyMatch(userRole -> userRole.implies(required));
    }

    /**
     * Checks if the security context has ANY of the required roles.
     */
    public static boolean hasAnyRole(OrionSecurityContext context, Role... required) {
        for (Role role : required) {
            if (hasRole(context, role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the security context has ALL of the required roles.
     */
    public static boolean hasAllRoles(OrionSecurityContext context, Role... required) {
        for (Role role : required) {
            if (!hasRole(context, role)) {
                return false;
            }
        }
        return true;
    }
}
