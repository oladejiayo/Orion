package com.orion.eventmodel;

import java.time.Instant;

/**
 * Canonical event envelope for all domain events in the Orion platform.
 *
 * <p>Every event published to Kafka MUST be wrapped in this envelope. The envelope carries standard
 * metadata (identification, correlation, multi-tenancy, versioning) alongside the domain-specific
 * payload.
 *
 * <p>This is a Java record — immutable by design. Once an event is created, it cannot be modified,
 * which is exactly the semantics we want for events.
 *
 * @param <T> the type of the domain-specific payload
 */
public record EventEnvelope<T>(
        /** Unique identifier for this event instance (UUID v4). */
        String eventId,

        /** The type/name of this event (e.g. "TradeExecuted"). */
        String eventType,

        /** Schema version of this event type — starts at 1, increments for breaking changes. */
        int eventVersion,

        /** When the event occurred (ISO 8601 with millisecond precision). */
        Instant occurredAt,

        /** Name of the service that produced this event. */
        String producer,

        /** Tenant identifier for multi-tenancy isolation. */
        String tenantId,

        /** Correlation ID linking related events across a distributed flow. */
        String correlationId,

        /** ID of the command or event that directly caused this event. */
        String causationId,

        /** The domain entity (aggregate root) this event relates to. */
        EventEntity entity,

        /** Domain-specific event data. */
        T payload) {}
