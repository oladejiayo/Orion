package com.orion.eventmodel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for EventType enum and EntityType enum (AC6: Event Type Registry).
 *
 * <p>WHY: Verify all expected event types exist, string lookup works, and the isKnown guard
 * functions correctly.
 */
@DisplayName("US-01-03 AC6: EventType & EntityType")
class EventTypeTest {

    @Nested
    @DisplayName("EventType enum")
    class EventTypeTests {

        @Test
        @DisplayName("has all PRD-defined event types")
        void hasAllEventTypes() {
            // Spot-check critical event types from the PRD
            assertThat(EventType.TRADE_EXECUTED.value()).isEqualTo("TradeExecuted");
            assertThat(EventType.RFQ_CREATED.value()).isEqualTo("RFQCreated");
            assertThat(EventType.QUOTE_RECEIVED.value()).isEqualTo("QuoteReceived");
            assertThat(EventType.ORDER_PLACED.value()).isEqualTo("OrderPlaced");
            assertThat(EventType.SETTLEMENT_COMPLETED.value()).isEqualTo("SettlementCompleted");
            assertThat(EventType.RISK_LIMIT_BREACHED.value()).isEqualTo("RiskLimitBreached");
            assertThat(EventType.KILL_SWITCH_ENABLED.value()).isEqualTo("KillSwitchEnabled");
        }

        @Test
        @DisplayName("has at least 25 event types")
        void hasMinimumCount() {
            assertThat(EventType.values().length).isGreaterThanOrEqualTo(25);
        }

        @Test
        @DisplayName("fromString returns correct enum for known type")
        void fromStringKnown() {
            var result = EventType.fromString("TradeExecuted");
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(EventType.TRADE_EXECUTED);
        }

        @Test
        @DisplayName("fromString returns empty for unknown type")
        void fromStringUnknown() {
            var result = EventType.fromString("SomeUnknownEvent");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("isKnown returns true for known type")
        void isKnownTrue() {
            assertThat(EventType.isKnown("RFQCreated")).isTrue();
        }

        @Test
        @DisplayName("isKnown returns false for unknown type")
        void isKnownFalse() {
            assertThat(EventType.isKnown("FakeEvent")).isFalse();
        }

        @Test
        @DisplayName("fromString is case-sensitive")
        void caseSensitive() {
            assertThat(EventType.fromString("tradeexecuted")).isEmpty();
            assertThat(EventType.fromString("TRADEEXECUTED")).isEmpty();
        }
    }

    @Nested
    @DisplayName("EntityType enum")
    class EntityTypeTests {

        @Test
        @DisplayName("has core entity types")
        void hasCoreTypes() {
            assertThat(EntityType.TRADE.value()).isEqualTo("Trade");
            assertThat(EntityType.RFQ.value()).isEqualTo("RFQ");
            assertThat(EntityType.ORDER.value()).isEqualTo("Order");
            assertThat(EntityType.INSTRUMENT.value()).isEqualTo("Instrument");
        }

        @Test
        @DisplayName("has at least 8 entity types")
        void hasMinimumCount() {
            assertThat(EntityType.values().length).isGreaterThanOrEqualTo(8);
        }
    }
}
