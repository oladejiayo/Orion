package com.orion.eventmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for EventValidator (AC5: Schema Validation).
 *
 * <p>WHY: Verify that validation catches missing/blank required fields and returns all errors at
 * once in a ValidationResult.
 */
@DisplayName("US-01-03 AC5: EventValidator")
class EventValidatorTest {

    private static final EventEntity VALID_ENTITY = new EventEntity("Trade", "t-1", 1);

    private EventEnvelope<String> validEvent() {
        return EventFactory.create("TradeExecuted", "exec-svc", "tenant-1", VALID_ENTITY, "data");
    }

    @Nested
    @DisplayName("valid events")
    class ValidEvents {

        @Test
        @DisplayName("factory-created event passes validation")
        void factoryEventValid() {
            var result = EventValidator.validate(validEvent());
            assertThat(result.valid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("missing required fields")
    class MissingFields {

        @Test
        @DisplayName("null eventId fails")
        void nullEventId() {
            var event =
                    new EventEnvelope<>(
                            null, "T", 1, Instant.now(), "s", "t", "c", "x", VALID_ENTITY, "p");
            var result = EventValidator.validate(event);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("eventid"));
        }

        @Test
        @DisplayName("blank eventType fails")
        void blankEventType() {
            var event =
                    new EventEnvelope<>(
                            "id", "  ", 1, Instant.now(), "s", "t", "c", "x", VALID_ENTITY, "p");
            var result = EventValidator.validate(event);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("eventtype"));
        }

        @Test
        @DisplayName("null occurredAt fails")
        void nullOccurredAt() {
            var event =
                    new EventEnvelope<>("id", "T", 1, null, "s", "t", "c", "x", VALID_ENTITY, "p");
            var result = EventValidator.validate(event);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("occurredat"));
        }

        @Test
        @DisplayName("blank producer fails")
        void blankProducer() {
            var event =
                    new EventEnvelope<>(
                            "id", "T", 1, Instant.now(), "", "t", "c", "x", VALID_ENTITY, "p");
            var result = EventValidator.validate(event);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("producer"));
        }

        @Test
        @DisplayName("blank tenantId fails")
        void blankTenantId() {
            var event =
                    new EventEnvelope<>(
                            "id", "T", 1, Instant.now(), "s", "", "c", "x", VALID_ENTITY, "p");
            var result = EventValidator.validate(event);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("tenantid"));
        }

        @Test
        @DisplayName("null entity fails")
        void nullEntity() {
            var event =
                    new EventEnvelope<>("id", "T", 1, Instant.now(), "s", "t", "c", "x", null, "p");
            var result = EventValidator.validate(event);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("entity"));
        }

        @Test
        @DisplayName("null entity.entityId fails")
        void nullEntityId() {
            var badEntity = new EventEntity("Trade", null, 1);
            var event =
                    new EventEnvelope<>(
                            "id", "T", 1, Instant.now(), "s", "t", "c", "x", badEntity, "p");
            var result = EventValidator.validate(event);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("entityid"));
        }
    }

    @Nested
    @DisplayName("version checks")
    class VersionChecks {

        @Test
        @DisplayName("eventVersion < 1 fails")
        void versionLessThanOne() {
            var event =
                    new EventEnvelope<>(
                            "id", "T", 0, Instant.now(), "s", "t", "c", "x", VALID_ENTITY, "p");
            var result = EventValidator.validate(event);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("version"));
        }
    }

    @Nested
    @DisplayName("multiple errors")
    class MultipleErrors {

        @Test
        @DisplayName("reports ALL errors, not just the first one")
        void reportsAllErrors() {
            var event = new EventEnvelope<>(null, "", 0, null, "", "", null, null, null, null);
            var result = EventValidator.validate(event);
            assertThat(result.valid()).isFalse();
            // Should have multiple errors — at least 5
            assertThat(result.errors().size()).isGreaterThanOrEqualTo(5);
        }
    }
}
