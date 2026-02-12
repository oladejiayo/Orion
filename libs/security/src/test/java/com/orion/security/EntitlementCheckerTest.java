package com.orion.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EntitlementChecker (AC4: ABAC — entitlement validation).
 *
 * WHY: Verify ABAC checks for asset class, instrument, venue restrictions,
 * and that empty sets mean "all allowed" (no restriction).
 */
@DisplayName("US-01-04 AC4: EntitlementChecker")
class EntitlementCheckerTest {

    private static final TradingLimits LIMITS = new TradingLimits(1_000_000.0, 60, 120, 100);

    @Nested
    @DisplayName("canTradeAssetClass()")
    class AssetClassCheck {

        @Test
        @DisplayName("returns true when asset class is in entitlements")
        void allowed() {
            var ent = new Entitlements(EnumSet.of(AssetClass.FX, AssetClass.RATES), Set.of(), Set.of(), LIMITS);
            assertThat(EntitlementChecker.canTradeAssetClass(ent, AssetClass.FX)).isTrue();
        }

        @Test
        @DisplayName("returns false when asset class is NOT in entitlements")
        void notAllowed() {
            var ent = new Entitlements(EnumSet.of(AssetClass.FX), Set.of(), Set.of(), LIMITS);
            assertThat(EntitlementChecker.canTradeAssetClass(ent, AssetClass.CREDIT)).isFalse();
        }

        @Test
        @DisplayName("empty asset class set means all allowed")
        void emptyMeansAll() {
            var ent = new Entitlements(EnumSet.noneOf(AssetClass.class), Set.of(), Set.of(), LIMITS);
            assertThat(EntitlementChecker.canTradeAssetClass(ent, AssetClass.COMMODITIES)).isTrue();
        }
    }

    @Nested
    @DisplayName("canTradeInstrument()")
    class InstrumentCheck {

        @Test
        @DisplayName("returns true when instrument is in entitlements")
        void allowed() {
            var ent = new Entitlements(EnumSet.allOf(AssetClass.class), Set.of("INST-1", "INST-2"), Set.of(), LIMITS);
            assertThat(EntitlementChecker.canTradeInstrument(ent, "INST-1")).isTrue();
        }

        @Test
        @DisplayName("returns false when instrument is NOT in entitlements")
        void notAllowed() {
            var ent = new Entitlements(EnumSet.allOf(AssetClass.class), Set.of("INST-1"), Set.of(), LIMITS);
            assertThat(EntitlementChecker.canTradeInstrument(ent, "INST-99")).isFalse();
        }

        @Test
        @DisplayName("empty instruments set means all allowed")
        void emptyMeansAll() {
            var ent = new Entitlements(EnumSet.allOf(AssetClass.class), Set.of(), Set.of(), LIMITS);
            assertThat(EntitlementChecker.canTradeInstrument(ent, "ANY-INSTRUMENT")).isTrue();
        }
    }

    @Nested
    @DisplayName("canAccessVenue()")
    class VenueCheck {

        @Test
        @DisplayName("returns true when venue is in entitlements")
        void allowed() {
            var ent = new Entitlements(EnumSet.allOf(AssetClass.class), Set.of(), Set.of("VEN-A"), LIMITS);
            assertThat(EntitlementChecker.canAccessVenue(ent, "VEN-A")).isTrue();
        }

        @Test
        @DisplayName("returns false when venue is NOT in entitlements")
        void notAllowed() {
            var ent = new Entitlements(EnumSet.allOf(AssetClass.class), Set.of(), Set.of("VEN-A"), LIMITS);
            assertThat(EntitlementChecker.canAccessVenue(ent, "VEN-B")).isFalse();
        }

        @Test
        @DisplayName("empty venues set means all allowed")
        void emptyMeansAll() {
            var ent = Entitlements.defaults();
            assertThat(EntitlementChecker.canAccessVenue(ent, "ANY-VENUE")).isTrue();
        }
    }

    @Nested
    @DisplayName("isWithinNotionalLimit()")
    class NotionalLimitCheck {

        @Test
        @DisplayName("returns true when notional is within limit")
        void withinLimit() {
            var ent = new Entitlements(EnumSet.allOf(AssetClass.class), Set.of(), Set.of(), LIMITS);
            assertThat(EntitlementChecker.isWithinNotionalLimit(ent, 500_000.0)).isTrue();
        }

        @Test
        @DisplayName("returns true when notional equals limit")
        void atLimit() {
            assertThat(EntitlementChecker.isWithinNotionalLimit(
                    new Entitlements(EnumSet.allOf(AssetClass.class), Set.of(), Set.of(), LIMITS),
                    1_000_000.0
            )).isTrue();
        }

        @Test
        @DisplayName("returns false when notional exceeds limit")
        void exceedsLimit() {
            assertThat(EntitlementChecker.isWithinNotionalLimit(
                    new Entitlements(EnumSet.allOf(AssetClass.class), Set.of(), Set.of(), LIMITS),
                    1_000_001.0
            )).isFalse();
        }
    }
}
