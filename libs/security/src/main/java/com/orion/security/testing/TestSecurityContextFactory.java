package com.orion.security.testing;

import com.orion.security.*;

import java.util.List;
import java.util.UUID;

/**
 * Factory for creating mock {@link OrionSecurityContext} instances in tests.
 * <p>
 * WHY in src/main: placed in the main source set so other modules can import
 * this class in their test scope via a regular Maven dependency. Avoids the
 * complexity of Maven test-jars.
 * <p>
 * Package: {@code com.orion.security.testing} — clearly signals "for tests only."
 */
public final class TestSecurityContextFactory {

    private TestSecurityContextFactory() {
        // utility class
    }

    /**
     * Creates a default test security context with TRADER role.
     */
    public static OrionSecurityContext create() {
        return create(
                "test-user-001", "test-tenant-001",
                List.of(Role.TRADER), Entitlements.defaults()
        );
    }

    /**
     * Creates a test security context with the given roles.
     */
    public static OrionSecurityContext createWithRoles(Role... roles) {
        return create(
                "test-user-001", "test-tenant-001",
                List.of(roles), Entitlements.defaults()
        );
    }

    /**
     * Creates a test security context for a specific tenant.
     */
    public static OrionSecurityContext createForTenant(String tenantId) {
        return create(
                "test-user-001", tenantId,
                List.of(Role.TRADER), Entitlements.defaults()
        );
    }

    /**
     * Creates a fully-customized test security context.
     */
    public static OrionSecurityContext create(
            String userId,
            String tenantId,
            List<Role> roles,
            Entitlements entitlements
    ) {
        return new OrionSecurityContext(
                new AuthenticatedUser(userId, userId + "@orion.local", userId, "Test User"),
                new TenantContext(tenantId, "Test Tenant", TenantType.STANDARD),
                roles,
                entitlements,
                "mock-jwt-token-" + UUID.randomUUID(),
                "test-correlation-" + UUID.randomUUID()
        );
    }
}
