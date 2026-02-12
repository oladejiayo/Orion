package com.orion.eventmodel;

import java.util.Optional;

/**
 * All known event types in the Orion platform.
 *
 * <p>WHY an enum: compile-time safety, exhaustive switch, easy to add new types. The {@code value}
 * field holds the canonical string used in JSON serialization.
 */
public enum EventType {

    // ---- Market Data Events ----
    MARKET_TICK_RECEIVED("MarketTickReceived"),
    MARKET_SNAPSHOT_UPDATED("MarketSnapshotUpdated"),
    MARKET_DATA_STALE_DETECTED("MarketDataStaleDetected"),

    // ---- RFQ Events ----
    RFQ_CREATED("RFQCreated"),
    RFQ_SENT("RFQSent"),
    QUOTE_RECEIVED("QuoteReceived"),
    RFQ_EXPIRED("RFQExpired"),
    QUOTE_ACCEPTED("QuoteAccepted"),
    RFQ_CANCELLED("RFQCancelled"),
    QUOTE_ACCEPTANCE_REJECTED("QuoteAcceptanceRejected"),

    // ---- Order Events ----
    ORDER_PLACED("OrderPlaced"),
    ORDER_ACKNOWLEDGED("OrderAcknowledged"),
    ORDER_REJECTED("OrderRejected"),
    ORDER_CANCELLED("OrderCancelled"),
    ORDER_AMENDED("OrderAmended"),
    ORDER_FILLED("OrderFilled"),

    // ---- Execution Events ----
    TRADE_EXECUTED("TradeExecuted"),

    // ---- Post-Trade Events ----
    TRADE_CONFIRMED("TradeConfirmed"),
    SETTLEMENT_REQUESTED("SettlementRequested"),
    SETTLEMENT_COMPLETED("SettlementCompleted"),
    SETTLEMENT_FAILED("SettlementFailed"),

    // ---- Risk / Admin Events ----
    RISK_LIMIT_BREACHED("RiskLimitBreached"),
    KILL_SWITCH_ENABLED("KillSwitchEnabled"),
    KILL_SWITCH_DISABLED("KillSwitchDisabled"),
    INSTRUMENT_UPDATED("InstrumentUpdated"),
    VENUE_UPDATED("VenueUpdated"),
    LP_CONFIG_UPDATED("LPConfigUpdated");

    private final String value;

    EventType(String value) {
        this.value = value;
    }

    /** The canonical string representation used in JSON (e.g. "TradeExecuted"). */
    public String value() {
        return value;
    }

    /**
     * Looks up an EventType by its canonical string value.
     *
     * @param value the string to match (e.g. "TradeExecuted")
     * @return the matching EventType, or empty if not found
     */
    public static Optional<EventType> fromString(String value) {
        for (EventType type : values()) {
            if (type.value.equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Checks whether a string corresponds to a known event type. */
    public static boolean isKnown(String value) {
        return fromString(value).isPresent();
    }
}
