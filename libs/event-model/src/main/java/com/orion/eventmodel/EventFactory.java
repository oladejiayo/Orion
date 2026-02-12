package com.orion.eventmodel;

import java.time.Instant;
import java.util.UUID;

/**
 * Factory methods for creating {@link EventEnvelope} instances.
 * <p>
 * WHY a factory: Encapsulates default value logic (UUID generation,
 * current timestamp, default version) so callers don't repeat boilerplate.
 */
public final class EventFactory {

    private EventFactory() {
        // utility class — no instantiation
    }

    /**
     * Creates a new event envelope with auto-generated eventId, timestamp, and correlationId.
     */
    public static <T> EventEnvelope<T> create(
            String eventType,
            String producer,
            String tenantId,
            EventEntity entity,
            T payload
    ) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                1,
                Instant.now(),
                producer,
                tenantId,
                UUID.randomUUID().toString(),
                "direct",
                entity,
                payload
        );
    }

    /**
     * Creates a new event envelope with explicit version.
     */
    public static <T> EventEnvelope<T> create(
            String eventType,
            int eventVersion,
            String producer,
            String tenantId,
            String correlationId,
            String causationId,
            EventEntity entity,
            T payload
    ) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                eventVersion,
                Instant.now(),
                producer,
                tenantId,
                correlationId,
                causationId,
                entity,
                payload
        );
    }

    /**
     * Creates a child event that inherits correlation context from a parent event.
     * The child's causationId is set to the parent's eventId.
     */
    public static <T> EventEnvelope<T> createChild(
            EventEnvelope<?> parent,
            String eventType,
            String producer,
            EventEntity entity,
            T payload
    ) {
        return new EventEnvelope<>(
                UUID.randomUUID().toString(),
                eventType,
                1,
                Instant.now(),
                producer,
                parent.tenantId(),
                parent.correlationId(),
                parent.eventId(),
                entity,
                payload
        );
    }
}
