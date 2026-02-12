package com.orion.security;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Platform roles as defined in PRD Section 5.2.
 * <p>
 * WHY an enum with hierarchy: compile-time safety, exhaustive switch,
 * and the role hierarchy (ADMIN implies TRADER/SALES/RISK/ANALYST)
 * is encoded once here instead of scattered across services.
 */
public enum Role {

    TRADER("ROLE_TRADER"),
    SALES("ROLE_SALES"),
    RISK("ROLE_RISK"),
    ANALYST("ROLE_ANALYST"),
    ADMIN("ROLE_ADMIN"),
    PLATFORM("ROLE_PLATFORM");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    /** The canonical string representation (e.g., "ROLE_TRADER"). */
    public String value() {
        return value;
    }

    /**
     * Returns the set of roles that this role implies (inherits).
     * <p>
     * Hierarchy from PRD:
     * <ul>
     *   <li>ADMIN implies TRADER, SALES, RISK, ANALYST</li>
     *   <li>SALES implies TRADER</li>
     *   <li>All others imply nothing</li>
     * </ul>
     */
    public Set<Role> impliedRoles() {
        return switch (this) {
            case ADMIN -> EnumSet.of(TRADER, SALES, RISK, ANALYST);
            case SALES -> EnumSet.of(TRADER);
            default -> EnumSet.noneOf(Role.class);
        };
    }

    /**
     * Checks whether this role implies the given role
     * (either directly or through the hierarchy).
     */
    public boolean implies(Role other) {
        return this == other || impliedRoles().contains(other);
    }

    /**
     * Looks up a Role by its canonical string value (e.g., "ROLE_TRADER").
     *
     * @param value the string to match
     * @return the matching Role, or empty if not found
     */
    public static Optional<Role> fromString(String value) {
        for (Role role : values()) {
            if (role.value.equals(value)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    /**
     * Checks whether a string corresponds to a known role.
     */
    public static boolean isKnown(String value) {
        return fromString(value).isPresent();
    }
}
