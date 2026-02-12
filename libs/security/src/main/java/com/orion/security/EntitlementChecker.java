package com.orion.security;

/**
 * Attribute-based access control (ABAC) entitlement checker.
 *
 * <p>WHY a utility class: ABAC checks whether a user is entitled to trade a specific asset class,
 * instrument, venue, or notional amount. Empty sets mean "no restriction."
 */
public final class EntitlementChecker {

    private EntitlementChecker() {
        // utility class
    }

    /**
     * Checks if the user can trade the given asset class. An empty assetClasses set in entitlements
     * means "all allowed."
     */
    public static boolean canTradeAssetClass(Entitlements entitlements, AssetClass assetClass) {
        // Empty set means "no restriction" — all asset classes allowed
        return entitlements.assetClasses().isEmpty()
                || entitlements.assetClasses().contains(assetClass);
    }

    /**
     * Checks if the user can trade the given instrument. An empty instruments set means "all
     * allowed."
     */
    public static boolean canTradeInstrument(Entitlements entitlements, String instrumentId) {
        return entitlements.instruments().isEmpty()
                || entitlements.instruments().contains(instrumentId);
    }

    /** Checks if the user can access the given venue. An empty venues set means "all allowed." */
    public static boolean canAccessVenue(Entitlements entitlements, String venueId) {
        return entitlements.venues().isEmpty() || entitlements.venues().contains(venueId);
    }

    /** Checks if the given notional amount is within the user's trading limits. */
    public static boolean isWithinNotionalLimit(Entitlements entitlements, double notional) {
        return notional <= entitlements.limits().maxNotional();
    }
}
