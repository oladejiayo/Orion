package com.orion.security;

/**
 * Per-user trading limits enforced by the platform.
 *
 * <p>WHY a record: immutable, easy to serialize, part of the ABAC entitlements. Limits are checked
 * before order/RFQ submission.
 *
 * @param maxNotional maximum notional value per trade (in base currency)
 * @param rfqRateLimit maximum RFQs per minute
 * @param orderRateLimit maximum orders per minute
 * @param maxOpenOrders maximum concurrent open orders
 */
public record TradingLimits(
        double maxNotional, int rfqRateLimit, int orderRateLimit, int maxOpenOrders) {

    /** Sensible defaults for standard-tier users. */
    public static TradingLimits defaults() {
        return new TradingLimits(10_000_000.0, 60, 120, 100);
    }
}
