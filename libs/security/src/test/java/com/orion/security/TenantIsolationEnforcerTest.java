package com.orion.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orion.security.testing.TestSecurityContextFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for TenantIsolationEnforcer (AC2: Tenant Context — isolation enforcement).
 *
 * <p>WHY: Verify that cross-tenant access is blocked with TenantMismatchException, and same-tenant
 * access passes silently.
 */
@DisplayName("US-01-04 AC2: TenantIsolationEnforcer")
class TenantIsolationEnforcerTest {

    @Nested
    @DisplayName("enforce()")
    class Enforce {

        @Test
        @DisplayName("passes when tenants match")
        void tenantsMatch() {
            var ctx = TestSecurityContextFactory.createForTenant("tenant-1");
            assertThatCode(() -> TenantIsolationEnforcer.enforce(ctx, "tenant-1"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws TenantMismatchException when tenants differ")
        void tenantsMismatch() {
            var ctx = TestSecurityContextFactory.createForTenant("tenant-1");
            assertThatThrownBy(() -> TenantIsolationEnforcer.enforce(ctx, "tenant-2"))
                    .isInstanceOf(TenantMismatchException.class)
                    .hasMessageContaining("tenant-1")
                    .hasMessageContaining("tenant-2");
        }

        @Test
        @DisplayName("exception carries both tenant IDs")
        void exceptionCarriesIds() {
            var ctx = TestSecurityContextFactory.createForTenant("t-a");
            try {
                TenantIsolationEnforcer.enforce(ctx, "t-b");
            } catch (TenantMismatchException e) {
                assertThat(e.expectedTenantId()).isEqualTo("t-a");
                assertThat(e.actualTenantId()).isEqualTo("t-b");
                return;
            }
            throw new AssertionError("Expected TenantMismatchException");
        }
    }
}
