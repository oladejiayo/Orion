package com.orion.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * ABAC (Attribute-Based Access Control) entitlements for a user.
 *
 * <p>WHY a record: captures the "what can this user access" question as an immutable value object.
 * Empty sets mean "no restrictions" (all allowed).
 *
 * @param assetClasses allowed asset classes (empty = all allowed)
 * @param instruments allowed instrument IDs (empty = all allowed)
 * @param venues allowed venue IDs (empty = all allowed)
 * @param limits trading limits for this user
 */
public record Entitlements(
        Set<AssetClass> assetClasses,
        Set<String> instruments,
        Set<String> venues,
        TradingLimits limits) {

    /**
     * Default entitlements: all asset classes, no instrument/venue restrictions, standard limits.
     */
    public static Entitlements defaults() {
        return new Entitlements(
                EnumSet.allOf(AssetClass.class), Set.of(), Set.of(), TradingLimits.defaults());
    }
}
