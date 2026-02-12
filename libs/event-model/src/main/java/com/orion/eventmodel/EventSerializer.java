package com.orion.eventmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Optional;

/**
 * JSON serialization and deserialization for {@link EventEnvelope}.
 * <p>
 * WHY Jackson: Spring Boot's default JSON library. The {@code JavaTimeModule}
 * handles {@code Instant} ↔ ISO 8601 string conversion automatically.
 */
public final class EventSerializer {

    private static final ObjectMapper MAPPER = createMapper();

    private EventSerializer() {
        // utility class
    }

    private static ObjectMapper createMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Serializes an event envelope to a JSON string.
     *
     * @throws EventSerializationException if serialization fails
     */
    public static String serialize(EventEnvelope<?> event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new EventSerializationException("Failed to serialize event: " + event.eventId(), e);
        }
    }

    /**
     * Deserializes a JSON string to an event envelope with a known payload type.
     *
     * @param json        the JSON string
     * @param payloadType the class of the payload
     * @throws EventSerializationException if deserialization fails or JSON is malformed
     */
    public static <T> EventEnvelope<T> deserialize(String json, Class<T> payloadType) {
        try {
            JavaType type = MAPPER.getTypeFactory()
                    .constructParametricType(EventEnvelope.class, payloadType);
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new EventSerializationException("Failed to deserialize event", e);
        }
    }

    /**
     * Safely deserializes, returning empty on failure.
     */
    public static <T> Optional<EventEnvelope<T>> tryDeserialize(String json, Class<T> payloadType) {
        try {
            return Optional.of(deserialize(json, payloadType));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Returns the shared ObjectMapper (for advanced use). */
    public static ObjectMapper objectMapper() {
        return MAPPER;
    }

    /**
     * Exception thrown when event serialization/deserialization fails.
     */
    public static class EventSerializationException extends RuntimeException {
        public EventSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
