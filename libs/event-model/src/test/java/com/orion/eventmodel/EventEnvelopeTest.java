package com.orion.eventmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the EventEnvelope and EventEntity records (AC1 + AC2).
 *
 * WHY: Verify that the canonical event envelope holds all required fields
 * and that Java records provide correct equality, immutability, and toString.
 */
@DisplayName("US-01-03 AC1/AC2: EventEnvelope & EventEntity")
class EventEnvelopeTest {

    private static final EventEntity SAMPLE_ENTITY =
            new EventEntity("Trade", "trade-123", 1);

    private static EventEnvelope<String> sampleEnvelope() {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                EventType.TRADE_EXECUTED.value(),
                1,
                Instant.now(),
                "execution-service",
                "tenant-001",
                UUID.randomUUID().toString(),
                "cmd-456",
                SAMPLE_ENTITY,
                "sample-payload"
        );
    }

    @Nested
    @DisplayName("EventEntity record")
    class EventEntityTests {

        @Test
        @DisplayName("holds entityType, entityId, and sequence")
        void holdsAllFields() {
            var entity = new EventEntity("RFQ", "rfq-789", 3);
            assertThat(entity.entityType()).isEqualTo("RFQ");
            assertThat(entity.entityId()).isEqualTo("rfq-789");
            assertThat(entity.sequence()).isEqualTo(3);
        }

        @Test
        @DisplayName("equals/hashCode based on all fields")
        void equalsAndHashCode() {
            var a = new EventEntity("Trade", "t-1", 1);
            var b = new EventEntity("Trade", "t-1", 1);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("not equal when fields differ")
        void notEqualWhenDifferent() {
            var a = new EventEntity("Trade", "t-1", 1);
            var b = new EventEntity("Trade", "t-1", 2);
            assertThat(a).isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("EventEnvelope record")
    class EventEnvelopeTests {

        @Test
        @DisplayName("holds all mandatory fields from PRD spec")
        void holdsAllFields() {
            var id = UUID.randomUUID().toString();
            var now = Instant.now();
            var corrId = UUID.randomUUID().toString();

            var event = new EventEnvelope<>(
                    id, "TradeExecuted", 1, now,
                    "execution-service", "tenant-001",
                    corrId, "cmd-001", SAMPLE_ENTITY, "payload"
            );

            assertThat(event.eventId()).isEqualTo(id);
            assertThat(event.eventType()).isEqualTo("TradeExecuted");
            assertThat(event.eventVersion()).isEqualTo(1);
            assertThat(event.occurredAt()).isEqualTo(now);
            assertThat(event.producer()).isEqualTo("execution-service");
            assertThat(event.tenantId()).isEqualTo("tenant-001");
            assertThat(event.correlationId()).isEqualTo(corrId);
            assertThat(event.causationId()).isEqualTo("cmd-001");
            assertThat(event.entity()).isEqualTo(SAMPLE_ENTITY);
            assertThat(event.payload()).isEqualTo("payload");
        }

        @Test
        @DisplayName("supports generic payload types")
        void supportsGenericPayload() {
            record TradePayload(double price, int quantity) {}

            var event = new EventEnvelope<>(
                    "id", "TradeExecuted", 1, Instant.now(),
                    "svc", "t1", "c1", "cmd1", SAMPLE_ENTITY,
                    new TradePayload(99.5, 100)
            );

            assertThat(event.payload().price()).isEqualTo(99.5);
            assertThat(event.payload().quantity()).isEqualTo(100);
        }

        @Test
        @DisplayName("records are equal when all fields match")
        void recordEquality() {
            var now = Instant.now();
            var a = new EventEnvelope<>("id", "T", 1, now, "s", "t", "c", "x", SAMPLE_ENTITY, "p");
            var b = new EventEnvelope<>("id", "T", 1, now, "s", "t", "c", "x", SAMPLE_ENTITY, "p");
            assertThat(a).isEqualTo(b);
        }

        @Test
        @DisplayName("toString includes field values (debuggable)")
        void toStringContainsFields() {
            var event = sampleEnvelope();
            assertThat(event.toString())
                    .contains("eventType=TradeExecuted")
                    .contains("producer=execution-service");
        }
    }
}
