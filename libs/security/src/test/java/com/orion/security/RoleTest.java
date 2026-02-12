package com.orion.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Role enum (AC3: RBAC — role definitions and hierarchy).
 *
 * <p>WHY: Verify all PRD roles exist, hierarchy is correct, and fromString lookup works.
 */
@DisplayName("US-01-04 AC3: Role enum")
class RoleTest {

    @Nested
    @DisplayName("enum values")
    class EnumValues {

        @Test
        @DisplayName("has all 6 PRD-defined roles")
        void hasAllRoles() {
            assertThat(Role.values()).hasSize(6);
            assertThat(Role.TRADER.value()).isEqualTo("ROLE_TRADER");
            assertThat(Role.SALES.value()).isEqualTo("ROLE_SALES");
            assertThat(Role.RISK.value()).isEqualTo("ROLE_RISK");
            assertThat(Role.ANALYST.value()).isEqualTo("ROLE_ANALYST");
            assertThat(Role.ADMIN.value()).isEqualTo("ROLE_ADMIN");
            assertThat(Role.PLATFORM.value()).isEqualTo("ROLE_PLATFORM");
        }
    }

    @Nested
    @DisplayName("hierarchy")
    class Hierarchy {

        @Test
        @DisplayName("ADMIN implies TRADER, SALES, RISK, ANALYST")
        void adminImpliesAll() {
            assertThat(Role.ADMIN.implies(Role.TRADER)).isTrue();
            assertThat(Role.ADMIN.implies(Role.SALES)).isTrue();
            assertThat(Role.ADMIN.implies(Role.RISK)).isTrue();
            assertThat(Role.ADMIN.implies(Role.ANALYST)).isTrue();
            assertThat(Role.ADMIN.implies(Role.ADMIN)).isTrue();
        }

        @Test
        @DisplayName("ADMIN does NOT imply PLATFORM")
        void adminDoesNotImplyPlatform() {
            assertThat(Role.ADMIN.implies(Role.PLATFORM)).isFalse();
        }

        @Test
        @DisplayName("SALES implies TRADER")
        void salesImpliesTrader() {
            assertThat(Role.SALES.implies(Role.TRADER)).isTrue();
            assertThat(Role.SALES.implies(Role.SALES)).isTrue();
        }

        @Test
        @DisplayName("SALES does NOT imply ADMIN or RISK")
        void salesDoesNotImplyAdmin() {
            assertThat(Role.SALES.implies(Role.ADMIN)).isFalse();
            assertThat(Role.SALES.implies(Role.RISK)).isFalse();
        }

        @Test
        @DisplayName("TRADER implies only itself")
        void traderImpliesOnlySelf() {
            assertThat(Role.TRADER.implies(Role.TRADER)).isTrue();
            assertThat(Role.TRADER.implies(Role.SALES)).isFalse();
            assertThat(Role.TRADER.implies(Role.ADMIN)).isFalse();
        }

        @Test
        @DisplayName("PLATFORM implies only itself")
        void platformImpliesOnlySelf() {
            assertThat(Role.PLATFORM.implies(Role.PLATFORM)).isTrue();
            assertThat(Role.PLATFORM.implies(Role.ADMIN)).isFalse();
            assertThat(Role.PLATFORM.implies(Role.TRADER)).isFalse();
        }

        @Test
        @DisplayName("impliedRoles returns correct sets")
        void impliedRolesCorrect() {
            assertThat(Role.ADMIN.impliedRoles())
                    .containsExactlyInAnyOrder(Role.TRADER, Role.SALES, Role.RISK, Role.ANALYST);
            assertThat(Role.SALES.impliedRoles()).containsExactly(Role.TRADER);
            assertThat(Role.TRADER.impliedRoles()).isEmpty();
            assertThat(Role.RISK.impliedRoles()).isEmpty();
            assertThat(Role.ANALYST.impliedRoles()).isEmpty();
            assertThat(Role.PLATFORM.impliedRoles()).isEmpty();
        }
    }

    @Nested
    @DisplayName("fromString()")
    class FromString {

        @Test
        @DisplayName("returns correct role for known value")
        void knownValue() {
            assertThat(Role.fromString("ROLE_TRADER")).contains(Role.TRADER);
            assertThat(Role.fromString("ROLE_ADMIN")).contains(Role.ADMIN);
        }

        @Test
        @DisplayName("returns empty for unknown value")
        void unknownValue() {
            assertThat(Role.fromString("ROLE_SUPERUSER")).isEmpty();
            assertThat(Role.fromString("TRADER")).isEmpty();
        }

        @Test
        @DisplayName("isKnown returns true for known role")
        void isKnownTrue() {
            assertThat(Role.isKnown("ROLE_RISK")).isTrue();
        }

        @Test
        @DisplayName("isKnown returns false for unknown role")
        void isKnownFalse() {
            assertThat(Role.isKnown("FAKE_ROLE")).isFalse();
        }
    }
}
