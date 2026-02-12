package com.orion.eventmodel;

/**
 * Validates {@link EventEnvelope} instances for required fields and format.
 *
 * <p>WHY manual validation over Bean Validation: no annotation-processing dependency, returns all
 * errors at once in a {@link ValidationResult}, and is fast.
 */
public final class EventValidator {

    private EventValidator() {
        // utility class
    }

    /**
     * Validates that all required fields of the event envelope are present and well-formed.
     *
     * @param event the event envelope to validate
     * @return a {@link ValidationResult} with any errors found
     */
    public static ValidationResult validate(EventEnvelope<?> event) {
        var errors = new java.util.ArrayList<String>();

        if (isBlank(event.eventId())) {
            errors.add("eventId must not be null or blank");
        }
        if (isBlank(event.eventType())) {
            errors.add("eventType must not be null or blank");
        }
        if (event.eventVersion() < 1) {
            errors.add("eventVersion must be >= 1");
        }
        if (event.occurredAt() == null) {
            errors.add("occurredAt must not be null");
        }
        if (isBlank(event.producer())) {
            errors.add("producer must not be null or blank");
        }
        if (isBlank(event.tenantId())) {
            errors.add("tenantId must not be null or blank");
        }
        if (event.entity() == null) {
            errors.add("entity must not be null");
        } else {
            if (isBlank(event.entity().entityId())) {
                errors.add("entity.entityId must not be null or blank");
            }
        }

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
