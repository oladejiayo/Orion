package com.orion.eventmodel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for EventSerializer (AC4: Serialization Utilities).
 *
 * WHY: Verify JSON round-trip, Instant serialization to ISO 8601,
 * generic payload handling, and error cases.
 */
@DisplayName("US-01-03 AC4: EventSerializer")
class EventSerializerTest {

    private static final EventEntity ENTITY = new EventEntity("Trade", "t-1", 1);

    private EventEnvelope<Map<String, Object>> sampleEvent() {
        return EventFactory.create(
                EventType.TRADE_EXECUTED.value(),
                "exec-svc", "tenant-1", ENTITY,
                Map.of("price", 99.5, "quantity", 100)
        );
    }

    @Nested
    @DisplayName("serialize()")
    class Serialize {

        @Test
        @DisplayName("produces valid JSON")
        void producesValidJson() {
            var event = sampleEvent();
            var json = EventSerializer.serialize(event);

            // Should be parseable as JSON
            assertThat(json).isNotBlank();
            assertThat(json).startsWith("{");
            assertThat(json).endsWith("}");
        }

        @Test
        @DisplayName("JSON contains all envelope fields")
        void containsAllFields() {
            var event = sampleEvent();
            var json = EventSerializer.serialize(event);

            assertThat(json)
                    .contains("eventId")
                    .contains("eventType")
                    .contains("eventVersion")
                    .contains("occurredAt")
                    .contains("producer")
                    .contains("tenantId")
                    .contains("correlationId")
                    .contains("causationId")
                    .contains("entity")
                    .contains("payload");
        }

        @Test
        @DisplayName("serializes Instant as ISO 8601 string (not numeric timestamp)")
        void instantAsIso8601() {
            var event = sampleEvent();
            var json = EventSerializer.serialize(event);

            // Should NOT contain raw epoch millis — should be ISO 8601 string
            // ISO 8601 format contains 'T' between date and time
            assertThat(json).containsPattern("\"occurredAt\"\\s*:\\s*\"\\d{4}-\\d{2}-\\d{2}T");
        }
    }

    @Nested
    @DisplayName("deserialize()")
    class Deserialize {

        @Test
        @DisplayName("round-trips correctly (serialize then deserialize)")
        void roundTrip() {
            var original = sampleEvent();
            var json = EventSerializer.serialize(original);

            @SuppressWarnings("unchecked")
            var restored = EventSerializer.deserialize(json, Map.class);

            assertThat(restored.eventId()).isEqualTo(original.eventId());
            assertThat(restored.eventType()).isEqualTo(original.eventType());
            assertThat(restored.eventVersion()).isEqualTo(original.eventVersion());
            assertThat(restored.occurredAt()).isEqualTo(original.occurredAt());
            assertThat(restored.producer()).isEqualTo(original.producer());
            assertThat(restored.tenantId()).isEqualTo(original.tenantId());
            assertThat(restored.correlationId()).isEqualTo(original.correlationId());
            assertThat(restored.causationId()).isEqualTo(original.causationId());
            assertThat(restored.entity()).isEqualTo(original.entity());
        }

        @Test
        @DisplayName("deserializes with typed payload")
        void typedPayload() {
            var event = EventFactory.create(
                    "TradeExecuted", "svc", "t1", ENTITY, "simple-string"
            );
            var json = EventSerializer.serialize(event);
            var restored = EventSerializer.deserialize(json, String.class);

            assertThat(restored.payload()).isEqualTo("simple-string");
        }

        @Test
        @DisplayName("throws on malformed JSON")
        void throwsOnMalformedJson() {
            assertThatThrownBy(() -> EventSerializer.deserialize("not-json{", String.class))
                    .isInstanceOf(EventSerializer.EventSerializationException.class);
        }

        @Test
        @DisplayName("throws on empty string")
        void throwsOnEmptyString() {
            assertThatThrownBy(() -> EventSerializer.deserialize("", String.class))
                    .isInstanceOf(EventSerializer.EventSerializationException.class);
        }
    }

    @Nested
    @DisplayName("tryDeserialize()")
    class TryDeserialize {

        @Test
        @DisplayName("returns Optional with valid JSON")
        void returnsPresent() {
            var event = sampleEvent();
            var json = EventSerializer.serialize(event);

            @SuppressWarnings("unchecked")
            var result = EventSerializer.tryDeserialize(json, Map.class);
            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("returns empty on invalid JSON")
        void returnsEmpty() {
            var result = EventSerializer.tryDeserialize("bad!", String.class);
            assertThat(result).isEmpty();
        }
    }
}
