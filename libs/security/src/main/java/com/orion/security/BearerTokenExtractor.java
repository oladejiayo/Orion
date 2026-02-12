package com.orion.security;

import java.util.Optional;

/**
 * Extracts bearer tokens from HTTP Authorization headers.
 * <p>
 * WHY a utility class: every service needs to extract the JWT from the
 * "Bearer xxx" header. Centralising this avoids subtle parsing bugs.
 */
public final class BearerTokenExtractor {

    private BearerTokenExtractor() {
        // utility class
    }

    /**
     * Extracts the bearer token from an Authorization header value.
     * <p>
     * Expects format: {@code "Bearer <token>"}
     *
     * @param authorizationHeader the full Authorization header value (may be null)
     * @return the token string, or empty if the header is missing/malformed
     */
    public static Optional<String> extract(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        // Case-insensitive match for "Bearer" prefix
        String trimmed = authorizationHeader.strip();
        if (!trimmed.toLowerCase().startsWith("bearer")) {
            return Optional.empty();
        }
        // Extract token after "Bearer" prefix, trimming whitespace
        String token = trimmed.substring("bearer".length()).strip();
        if (token.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(token);
    }
}
