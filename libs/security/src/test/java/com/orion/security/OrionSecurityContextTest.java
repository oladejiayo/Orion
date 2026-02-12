package com.orion.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for domain records (AC1/AC2: Security Context, Tenant, User records).
 *
 * <p>WHY: Verify all security domain records hold correct fields, provide equality, and support
 * default factories.
 */
@DisplayName("US-01-04 AC1/AC2: Security Domain Records")
class OrionSecurityContextTest {

    @Nested
    @DisplayName("AuthenticatedUser")
    class AuthenticatedUserTests {

        @Test
        @DisplayName("holds all fields")
        void holdsAllFields() {
            var user = new AuthenticatedUser("u-1", "a@b.com", "auser", "Alice");
            assertThat(user.userId()).isEqualTo("u-1");
            assertThat(user.email()).isEqualTo("a@b.com");
            assertThat(user.username()).isEqualTo("auser");
            assertThat(user.displayName()).isEqualTo("Alice");
        }

        @Test
        @DisplayName("equals based on all fields")
        void equality() {
            var a = new AuthenticatedUser("u-1", "a@b.com", "auser", "Alice");
            var b = new AuthenticatedUser("u-1", "a@b.com", "auser", "Alice");
            assertThat(a).isEqualTo(b);
        }
    }

    @Nested
    @DisplayName("TenantContext")
    class TenantContextTests {

        @Test
        @DisplayName("holds tenantId, tenantName, tenantType")
        void holdsAllFields() {
            var tenant = new TenantContext("t-1", "Acme Corp", TenantType.ENTERPRISE);
            assertThat(tenant.tenantId()).isEqualTo("t-1");
            assertThat(tenant.tenantName()).isEqualTo("Acme Corp");
            assertThat(tenant.tenantType()).isEqualTo(TenantType.ENTERPRISE);
        }
    }

    @Nested
    @DisplayName("TradingLimits")
    class TradingLimitsTests {

        @Test
        @DisplayName("holds all limit fields")
        void holdsAllFields() {
            var limits = new TradingLimits(5_000_000.0, 30, 60, 50);
            assertThat(limits.maxNotional()).isEqualTo(5_000_000.0);
            assertThat(limits.rfqRateLimit()).isEqualTo(30);
            assertThat(limits.orderRateLimit()).isEqualTo(60);
            assertThat(limits.maxOpenOrders()).isEqualTo(50);
        }

        @Test
        @DisplayName("defaults() returns sensible values")
        void defaults() {
            var defaults = TradingLimits.defaults();
            assertThat(defaults.maxNotional()).isGreaterThan(0);
            assertThat(defaults.rfqRateLimit()).isGreaterThan(0);
            assertThat(defaults.orderRateLimit()).isGreaterThan(0);
            assertThat(defaults.maxOpenOrders()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("Entitlements")
    class EntitlementsTests {

        @Test
        @DisplayName("holds asset classes, instruments, venues, limits")
        void holdsAllFields() {
            var ent =
                    new Entitlements(
                            EnumSet.of(AssetClass.FX, AssetClass.RATES),
                            Set.of("INST-1"),
                            Set.of("VEN-1"),
                            TradingLimits.defaults());
            assertThat(ent.assetClasses())
                    .containsExactlyInAnyOrder(AssetClass.FX, AssetClass.RATES);
            assertThat(ent.instruments()).containsExactly("INST-1");
            assertThat(ent.venues()).containsExactly("VEN-1");
            assertThat(ent.limits()).isNotNull();
        }

        @Test
        @DisplayName("defaults() has all asset classes and empty restrictions")
        void defaults() {
            var defaults = Entitlements.defaults();
            assertThat(defaults.assetClasses()).containsAll(EnumSet.allOf(AssetClass.class));
            assertThat(defaults.instruments()).isEmpty();
            assertThat(defaults.venues()).isEmpty();
        }
    }

    @Nested
    @DisplayName("OrionSecurityContext")
    class ContextTests {

        @Test
        @DisplayName("holds all security context fields")
        void holdsAllFields() {
            var user = new AuthenticatedUser("u-1", "a@b.com", "auser", "Alice");
            var tenant = new TenantContext("t-1", "Acme", TenantType.STANDARD);
            var ctx =
                    new OrionSecurityContext(
                            user,
                            tenant,
                            List.of(Role.TRADER),
                            Entitlements.defaults(),
                            "jwt-token",
                            "corr-123");
            assertThat(ctx.user()).isEqualTo(user);
            assertThat(ctx.tenant()).isEqualTo(tenant);
            assertThat(ctx.roles()).containsExactly(Role.TRADER);
            assertThat(ctx.entitlements()).isNotNull();
            assertThat(ctx.token()).isEqualTo("jwt-token");
            assertThat(ctx.correlationId()).isEqualTo("corr-123");
        }
    }

    @Nested
    @DisplayName("AssetClass enum")
    class AssetClassTests {

        @Test
        @DisplayName("has all 5 asset classes")
        void hasAllAssetClasses() {
            assertThat(AssetClass.values()).hasSize(5);
            assertThat(AssetClass.FX.value()).isEqualTo("FX");
            assertThat(AssetClass.RATES.value()).isEqualTo("RATES");
            assertThat(AssetClass.CREDIT.value()).isEqualTo("CREDIT");
            assertThat(AssetClass.EQUITIES.value()).isEqualTo("EQUITIES");
            assertThat(AssetClass.COMMODITIES.value()).isEqualTo("COMMODITIES");
        }
    }

    @Nested
    @DisplayName("TenantType enum")
    class TenantTypeTests {

        @Test
        @DisplayName("has all 3 tenant tiers")
        void hasAllTiers() {
            assertThat(TenantType.values()).hasSize(3);
            assertThat(TenantType.STANDARD.value()).isEqualTo("standard");
            assertThat(TenantType.PREMIUM.value()).isEqualTo("premium");
            assertThat(TenantType.ENTERPRISE.value()).isEqualTo("enterprise");
        }
    }

    @Nested
    @DisplayName("SecurityValidationResult")
    class ValidationResultTests {

        @Test
        @DisplayName("ok() returns valid result with no errors")
        void okResult() {
            var result = SecurityValidationResult.ok();
            assertThat(result.valid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("fail() returns invalid result with errors")
        void failResult() {
            var result =
                    SecurityValidationResult.fail(List.of("missing userId", "missing tenantId"));
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).hasSize(2);
        }
    }
}
