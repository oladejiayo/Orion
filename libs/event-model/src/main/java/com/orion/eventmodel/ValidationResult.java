package com.orion.eventmodel;

import java.util.List;

/**
 * Result of validating an {@link EventEnvelope}.
 *
 * @param valid true if validation passed with no errors
 * @param errors list of human-readable error messages (empty when valid)
 */
public record ValidationResult(boolean valid, List<String> errors) {

    /** Convenience factory for a successful validation. */
    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    /** Convenience factory for a failed validation. */
    public static ValidationResult fail(List<String> errors) {
        return new ValidationResult(false, List.copyOf(errors));
    }
}
