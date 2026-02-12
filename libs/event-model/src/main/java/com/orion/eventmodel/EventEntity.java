package com.orion.eventmodel;

/**
 * Tracks the domain entity (aggregate root) that an event relates to.
 *
 * @param entityType the kind of entity, e.g. "Trade", "RFQ", "Order"
 * @param entityId unique identifier of the entity instance
 * @param sequence monotonically increasing event number for this entity (for ordering)
 */
public record EventEntity(String entityType, String entityId, long sequence) {}
