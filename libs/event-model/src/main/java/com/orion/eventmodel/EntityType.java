package com.orion.eventmodel;

/** All known entity types (aggregate roots) in the Orion platform. */
public enum EntityType {
    TRADE("Trade"),
    RFQ("RFQ"),
    ORDER("Order"),
    QUOTE("Quote"),
    INSTRUMENT("Instrument"),
    VENUE("Venue"),
    MARKET_DATA("MarketData"),
    LP_CONFIG("LPConfig"),
    SETTLEMENT("Settlement"),
    RISK_LIMIT("RiskLimit");

    private final String value;

    EntityType(String value) {
        this.value = value;
    }

    /** Canonical string representation. */
    public String value() {
        return value;
    }
}
