package com.orion.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;

/**
 * Serializes and deserializes {@link OrionSecurityContext} for
 * service-to-service propagation (e.g., gRPC metadata).
 * <p>
 * WHY JSON + Base64: gRPC metadata values are strings. We serialize the security
 * context to JSON, then Base64-encode it for safe transport in metadata headers.
 */
public final class SecurityContextSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SecurityContextSerializer() {
        // utility class
    }

    /**
     * Serializes a security context to a Base64-encoded JSON string.
     *
     * @param context the security context to serialize
     * @return Base64-encoded JSON representation
     * @throws SecuritySerializationException if serialization fails
     */
    public static String serialize(OrionSecurityContext context) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(context);
            return Base64.getEncoder().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new SecuritySerializationException("Failed to serialize security context", e);
        }
    }

    /**
     * Deserializes a Base64-encoded JSON string back to a security context.
     *
     * @param encoded the Base64-encoded JSON string
     * @return the deserialized security context
     * @throws SecuritySerializationException if deserialization fails
     */
    public static OrionSecurityContext deserialize(String encoded) {
        try {
            byte[] json = Base64.getDecoder().decode(encoded);
            return MAPPER.readValue(json, OrionSecurityContext.class);
        } catch (Exception e) {
            throw new SecuritySerializationException("Failed to deserialize security context", e);
        }
    }

    /**
     * Exception thrown when security context serialization/deserialization fails.
     */
    public static class SecuritySerializationException extends RuntimeException {
        public SecuritySerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
