package com.orion.security;

import java.util.List;

/**
 * Result of validating an {@link OrionSecurityContext}.
 * <p>
 * WHY a record: immutable, clear API — either valid (empty errors) or invalid (errors list).
 * Same pattern as {@code ValidationResult} in event-model.
 *
 * @param valid  whether the context passed all validation checks
 * @param errors list of validation error messages (empty if valid)
 */
public record SecurityValidationResult(boolean valid, List<String> errors) {

    /** Creates a passing result. */
    public static SecurityValidationResult ok() {
        return new SecurityValidationResult(true, List.of());
    }

    /** Creates a failing result with one or more error messages. */
    public static SecurityValidationResult fail(List<String> errors) {
        return new SecurityValidationResult(false, List.copyOf(errors));
    }
}
