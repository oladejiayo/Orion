package com.orion.security;

import com.orion.security.testing.TestSecurityContextFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RoleChecker (AC3: RBAC — role checking with hierarchy).
 *
 * WHY: Verify that RBAC checks respect the role hierarchy
 * (ADMIN implies TRADER, SALES implies TRADER, etc.).
 */
@DisplayName("US-01-04 AC3: RoleChecker")
class RoleCheckerTest {

    @Nested
    @DisplayName("hasRole()")
    class HasRole {

        @Test
        @DisplayName("returns true when user has exact role")
        void exactRole() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.TRADER);
            assertThat(RoleChecker.hasRole(ctx, Role.TRADER)).isTrue();
        }

        @Test
        @DisplayName("returns false when user lacks role")
        void lacksRole() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.TRADER);
            assertThat(RoleChecker.hasRole(ctx, Role.ADMIN)).isFalse();
        }

        @Test
        @DisplayName("ADMIN satisfies TRADER check via hierarchy")
        void adminSatisfiesTrader() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.ADMIN);
            assertThat(RoleChecker.hasRole(ctx, Role.TRADER)).isTrue();
            assertThat(RoleChecker.hasRole(ctx, Role.SALES)).isTrue();
            assertThat(RoleChecker.hasRole(ctx, Role.RISK)).isTrue();
            assertThat(RoleChecker.hasRole(ctx, Role.ANALYST)).isTrue();
        }

        @Test
        @DisplayName("SALES satisfies TRADER check via hierarchy")
        void salesSatisfiesTrader() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.SALES);
            assertThat(RoleChecker.hasRole(ctx, Role.TRADER)).isTrue();
        }

        @Test
        @DisplayName("TRADER does NOT satisfy ADMIN")
        void traderDoesNotSatisfyAdmin() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.TRADER);
            assertThat(RoleChecker.hasRole(ctx, Role.ADMIN)).isFalse();
        }
    }

    @Nested
    @DisplayName("hasAnyRole()")
    class HasAnyRole {

        @Test
        @DisplayName("returns true when user has one of the required roles")
        void hasOne() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.RISK);
            assertThat(RoleChecker.hasAnyRole(ctx, Role.TRADER, Role.RISK)).isTrue();
        }

        @Test
        @DisplayName("returns false when user has none of the required roles")
        void hasNone() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.TRADER);
            assertThat(RoleChecker.hasAnyRole(ctx, Role.ADMIN, Role.RISK)).isFalse();
        }

        @Test
        @DisplayName("hierarchy applies to anyRole check")
        void hierarchyApplies() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.ADMIN);
            assertThat(RoleChecker.hasAnyRole(ctx, Role.TRADER, Role.RISK)).isTrue();
        }
    }

    @Nested
    @DisplayName("hasAllRoles()")
    class HasAllRoles {

        @Test
        @DisplayName("returns true when user has all required roles")
        void hasAll() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.TRADER, Role.RISK);
            assertThat(RoleChecker.hasAllRoles(ctx, Role.TRADER, Role.RISK)).isTrue();
        }

        @Test
        @DisplayName("returns false when user is missing one required role")
        void missingOne() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.TRADER);
            assertThat(RoleChecker.hasAllRoles(ctx, Role.TRADER, Role.RISK)).isFalse();
        }

        @Test
        @DisplayName("ADMIN satisfies all non-PLATFORM roles via hierarchy")
        void adminSatisfiesAll() {
            var ctx = TestSecurityContextFactory.createWithRoles(Role.ADMIN);
            assertThat(RoleChecker.hasAllRoles(ctx, Role.TRADER, Role.SALES, Role.RISK)).isTrue();
        }
    }
}
