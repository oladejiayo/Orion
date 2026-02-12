package com.orion.security;

import com.orion.security.testing.TestSecurityContextFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TestSecurityContextFactory (AC8: Testing utilities).
 *
 * WHY: Verify that test factories produce valid, customizable security contexts
 * that other modules can use in their test suites.
 */
@DisplayName("US-01-04 AC8: TestSecurityContextFactory")
class TestSecurityContextFactoryTest {

    @Nested
    @DisplayName("create()")
    class DefaultCreate {

        @Test
        @DisplayName("creates a context with TRADER role by default")
        void defaultRole() {
            var ctx = TestSecurityContextFactory.create();
            assertThat(ctx.roles()).containsExactly(Role.TRADER);
        }

        @Test
        @DisplayName("creates a context with non-blank user and tenant")
        void hasUserAndTenant() {
            var ctx = TestSecurityContextFactory.create();
            assertThat(ctx.user().userId()).isNotBlank();
            assertThat(ctx.user().email()).isNotBlank();
            assertThat(ctx.tenant().tenantId()).isNotBlank();
        }

        @Test
        @DisplayName("creates a context with default entitlements")
        void hasEntitlements() {
            var ctx = TestSecurityContextFactory.create();
            assertThat(ctx.entitlements()).isNotNull();
            assertThat(ctx.entitlements().assetClasses()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("createWithRoles()")
    class WithRoles {

        @Test
        @DisplayName("creates a context with specified roles")
        void specifiedRoles() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.ADMIN, Role.RISK);
            assertThat(ctx.roles()).containsExactlyInAnyOrder(Role.ADMIN, Role.RISK);
        }
    }

    @Nested
    @DisplayName("createForTenant()")
    class ForTenant {

        @Test
        @DisplayName("creates a context with specified tenant ID")
        void specifiedTenant() {
            var ctx = TestSecurityContextFactory.createForTenant("my-tenant");
            assertThat(ctx.tenant().tenantId()).isEqualTo("my-tenant");
        }
    }

    @Nested
    @DisplayName("create(full params)")
    class FullCreate {

        @Test
        @DisplayName("creates a fully customized context")
        void fullyCustomized() {
            var ctx = TestSecurityContextFactory.create(
                    "custom-user", "custom-tenant",
                    List.of(Role.PLATFORM), Entitlements.defaults()
            );
            assertThat(ctx.user().userId()).isEqualTo("custom-user");
            assertThat(ctx.tenant().tenantId()).isEqualTo("custom-tenant");
            assertThat(ctx.roles()).containsExactly(Role.PLATFORM);
        }
    }
}
