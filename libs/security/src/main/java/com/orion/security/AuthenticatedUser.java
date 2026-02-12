package com.orion.security;

/**
 * Authenticated user information extracted from a validated JWT.
 * <p>
 * WHY a record: immutable, thread-safe, auto-generated equals/hashCode/toString.
 * This is the "who" in every security check.
 *
 * @param userId      unique user identifier (from JWT 'sub' claim)
 * @param email       user's email address
 * @param username    login username or preferred_username
 * @param displayName optional human-readable display name
 */
public record AuthenticatedUser(
        String userId,
        String email,
        String username,
        String displayName
) {
}
