package com.orion.eventmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EventFactory (AC3: Event Factory Functions).
 *
 * WHY: Verify that factory methods generate valid events with
 * auto-generated UUIDs, timestamps, and sensible defaults.
 */
@DisplayName("US-01-03 AC3: EventFactory")
class EventFactoryTest {

    private static final EventEntity ENTITY =
            new EventEntity("Trade", "trade-001", 1);

    @Nested
    @DisplayName("create() with minimal params")
    class CreateMinimal {

        @Test
        @DisplayName("generates a UUID eventId")
        void generatesEventId() {
            var event = EventFactory.create(
                    EventType.TRADE_EXECUTED.value(), "exec-svc", "tenant-1", ENTITY, "payload"
            );
            assertThat(event.eventId()).isNotBlank();
            // UUID format: 8-4-4-4-12 hex digits
            assertThat(event.eventId()).matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
            );
        }

        @Test
        @DisplayName("generates unique IDs for different events")
        void uniqueEventIds() {
            var e1 = EventFactory.create("T", "s", "t", ENTITY, "p");
            var e2 = EventFactory.create("T", "s", "t", ENTITY, "p");
            assertThat(e1.eventId()).isNotEqualTo(e2.eventId());
        }

        @Test
        @DisplayName("sets occurredAt to approximately now")
        void setsTimestamp() {
            var before = Instant.now();
            var event = EventFactory.create("T", "s", "t", ENTITY, "p");
            var after = Instant.now();

            assertThat(event.occurredAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("defaults eventVersion to 1")
        void defaultsVersion() {
            var event = EventFactory.create("T", "s", "t", ENTITY, "p");
            assertThat(event.eventVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("auto-generates correlationId")
        void autoCorrelationId() {
            var event = EventFactory.create("T", "s", "t", ENTITY, "p");
            assertThat(event.correlationId()).isNotBlank();
        }

        @Test
        @DisplayName("defaults causationId to 'direct'")
        void defaultCausationId() {
            var event = EventFactory.create("T", "s", "t", ENTITY, "p");
            assertThat(event.causationId()).isEqualTo("direct");
        }

        @Test
        @DisplayName("carries through eventType, producer, tenantId, entity, payload")
        void carriesThroughFields() {
            var event = EventFactory.create(
                    "TradeExecuted", "exec-svc", "tenant-42", ENTITY, "my-payload"
            );
            assertThat(event.eventType()).isEqualTo("TradeExecuted");
            assertThat(event.producer()).isEqualTo("exec-svc");
            assertThat(event.tenantId()).isEqualTo("tenant-42");
            assertThat(event.entity()).isEqualTo(ENTITY);
            assertThat(event.payload()).isEqualTo("my-payload");
        }
    }

    @Nested
    @DisplayName("create() with full params")
    class CreateFull {

        @Test
        @DisplayName("uses explicit version, correlationId, causationId")
        void explicitParams() {
            var event = EventFactory.create(
                    "RFQCreated", 3, "rfq-svc", "tenant-1",
                    "corr-999", "cause-888", ENTITY, "payload"
            );
            assertThat(event.eventVersion()).isEqualTo(3);
            assertThat(event.correlationId()).isEqualTo("corr-999");
            assertThat(event.causationId()).isEqualTo("cause-888");
        }
    }

    @Nested
    @DisplayName("createChild()")
    class CreateChild {

        @Test
        @DisplayName("inherits correlationId from parent")
        void inheritsCorrelationId() {
            var parent = EventFactory.create(
                    "RFQCreated", "rfq-svc", "tenant-1", ENTITY, "rfq-data"
            );
            var child = EventFactory.createChild(
                    parent, "QuoteReceived", "quote-svc",
                    new EventEntity("Quote", "q-1", 1), "quote-data"
            );
            assertThat(child.correlationId()).isEqualTo(parent.correlationId());
        }

        @Test
        @DisplayName("sets causationId to parent's eventId")
        void setsCausationIdToParentEventId() {
            var parent = EventFactory.create(
                    "RFQCreated", "rfq-svc", "tenant-1", ENTITY, "data"
            );
            var child = EventFactory.createChild(
                    parent, "QuoteReceived", "quote-svc",
                    new EventEntity("Quote", "q-1", 1), "data"
            );
            assertThat(child.causationId()).isEqualTo(parent.eventId());
        }

        @Test
        @DisplayName("inherits tenantId from parent")
        void inheritsTenantId() {
            var parent = EventFactory.create(
                    "RFQCreated", "rfq-svc", "tenant-xyz", ENTITY, "data"
            );
            var child = EventFactory.createChild(
                    parent, "QuoteReceived", "quote-svc",
                    new EventEntity("Quote", "q-1", 1), "data"
            );
            assertThat(child.tenantId()).isEqualTo("tenant-xyz");
        }

        @Test
        @DisplayName("has its own unique eventId")
        void ownEventId() {
            var parent = EventFactory.create(
                    "RFQCreated", "rfq-svc", "t", ENTITY, "d"
            );
            var child = EventFactory.createChild(
                    parent, "QuoteReceived", "q-svc", ENTITY, "d"
            );
            assertThat(child.eventId()).isNotEqualTo(parent.eventId());
        }
    }
}
