package com.orion.security;

/**
 * Tradeable asset classes supported by the Orion platform.
 *
 * <p>WHY an enum: asset classes are a fixed set defined by the business. Used in ABAC entitlements
 * to restrict which markets a user can access.
 */
public enum AssetClass {
    FX("FX"),
    RATES("RATES"),
    CREDIT("CREDIT"),
    EQUITIES("EQUITIES"),
    COMMODITIES("COMMODITIES");

    private final String value;

    AssetClass(String value) {
        this.value = value;
    }

    /** The canonical string representation. */
    public String value() {
        return value;
    }
}
