package com.orion.security;

/**
 * Validates that an {@link OrionSecurityContext} has all required fields populated.
 * <p>
 * WHY manual validation: same pattern as EventValidator — no annotation processing
 * dependency, returns all errors at once, and is fast.
 */
public final class SecurityContextValidator {

    private SecurityContextValidator() {
        // utility class
    }

    /**
     * Validates that all required fields of the security context are present and well-formed.
     *
     * @param context the security context to validate
     * @return a {@link SecurityValidationResult} with any errors found
     */
    public static SecurityValidationResult validate(OrionSecurityContext context) {
        var errors = new java.util.ArrayList<String>();

        if (context.user() == null) {
            errors.add("user must not be null");
        } else {
            if (isBlank(context.user().userId())) {
                errors.add("user.userId must not be null or blank");
            }
        }

        if (context.tenant() == null) {
            errors.add("tenant must not be null");
        } else {
            if (isBlank(context.tenant().tenantId())) {
                errors.add("tenant.tenantId must not be null or blank");
            }
        }

        if (context.roles() == null || context.roles().isEmpty()) {
            errors.add("roles must contain at least one role");
        }

        if (context.entitlements() == null) {
            errors.add("entitlements must not be null");
        }

        return errors.isEmpty() ? SecurityValidationResult.ok() : SecurityValidationResult.fail(errors);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
