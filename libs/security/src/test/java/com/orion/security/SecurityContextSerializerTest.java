package com.orion.security;

import com.orion.security.testing.TestSecurityContextFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SecurityContextSerializer (AC7: Security Context Propagation).
 *
 * WHY: Verify JSON+Base64 round-trip of security context for gRPC metadata transport.
 */
@DisplayName("US-01-04 AC7: SecurityContextSerializer")
class SecurityContextSerializerTest {

    @Nested
    @DisplayName("serialize / deserialize round-trip")
    class RoundTrip {

        @Test
        @DisplayName("round-trips correctly")
        void roundTrips() {
            var original = TestSecurityContextFactory.create();
            var encoded = SecurityContextSerializer.serialize(original);
            var restored = SecurityContextSerializer.deserialize(encoded);

            assertThat(restored.user().userId()).isEqualTo(original.user().userId());
            assertThat(restored.user().email()).isEqualTo(original.user().email());
            assertThat(restored.tenant().tenantId()).isEqualTo(original.tenant().tenantId());
            assertThat(restored.roles()).isEqualTo(original.roles());
            assertThat(restored.correlationId()).isEqualTo(original.correlationId());
        }

        @Test
        @DisplayName("serialized form is a non-empty Base64 string")
        void serializedForm() {
            var ctx = TestSecurityContextFactory.create();
            var encoded = SecurityContextSerializer.serialize(ctx);
            assertThat(encoded).isNotBlank();
            // Base64 strings only contain [A-Za-z0-9+/=]
            assertThat(encoded).matches("[A-Za-z0-9+/=]+");
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("throws on invalid Base64 input")
        void invalidBase64() {
            assertThatThrownBy(() -> SecurityContextSerializer.deserialize("not-valid-base64!!!"))
                    .isInstanceOf(SecurityContextSerializer.SecuritySerializationException.class);
        }

        @Test
        @DisplayName("throws on invalid JSON (valid Base64 but not JSON)")
        void invalidJson() {
            var encoded = java.util.Base64.getEncoder().encodeToString("not json".getBytes());
            assertThatThrownBy(() -> SecurityContextSerializer.deserialize(encoded))
                    .isInstanceOf(SecurityContextSerializer.SecuritySerializationException.class);
        }
    }
}
