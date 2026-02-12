package com.orion.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SensitiveDataRedactor} — validates field redaction,
 * case insensitivity, custom patterns, and edge cases.
 */
@DisplayName("SensitiveDataRedactor")
class SensitiveDataRedactorTest {

    private final SensitiveDataRedactor redactor = new SensitiveDataRedactor();

    @Nested
    @DisplayName("Default patterns")
    class DefaultPatterns {

        @Test
        @DisplayName("should redact 'password' field")
        void shouldRedactPassword() {
            Map<String, Object> data = Map.of("username", "jane", "password", "s3cr3t");
            Map<String, Object> result = redactor.redact(data);

            assertThat(result.get("username")).isEqualTo("jane");
            assertThat(result.get("password")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }

        @Test
        @DisplayName("should redact 'token' field")
        void shouldRedactToken() {
            Map<String, Object> data = Map.of("token", "eyJhbGciOi...");
            assertThat(redactor.redact(data).get("token")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }

        @Test
        @DisplayName("should redact 'accessToken' field")
        void shouldRedactAccessToken() {
            Map<String, Object> data = Map.of("accessToken", "abc123");
            assertThat(redactor.redact(data).get("accessToken")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }

        @Test
        @DisplayName("should redact 'refreshToken' field")
        void shouldRedactRefreshToken() {
            Map<String, Object> data = Map.of("refreshToken", "xyz789");
            assertThat(redactor.redact(data).get("refreshToken")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }

        @Test
        @DisplayName("should redact 'secret' field")
        void shouldRedactSecret() {
            Map<String, Object> data = Map.of("secret", "my-secret-value");
            assertThat(redactor.redact(data).get("secret")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }

        @Test
        @DisplayName("should redact 'authorization' field")
        void shouldRedactAuthorization() {
            Map<String, Object> data = Map.of("authorization", "Bearer eyJ...");
            assertThat(redactor.redact(data).get("authorization")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }

        @Test
        @DisplayName("should redact 'apiKey' field")
        void shouldRedactApiKey() {
            Map<String, Object> data = Map.of("apiKey", "AKIAIOSFODNN7");
            assertThat(redactor.redact(data).get("apiKey")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }

        @Test
        @DisplayName("should redact 'credential' field")
        void shouldRedactCredential() {
            Map<String, Object> data = Map.of("credential", "cred-value");
            assertThat(redactor.redact(data).get("credential")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }

        @Test
        @DisplayName("should preserve non-sensitive fields")
        void shouldPreserveNonSensitiveFields() {
            Map<String, Object> data = Map.of(
                    "tradeId", "trade-123",
                    "amount", 1000,
                    "currency", "USD"
            );
            Map<String, Object> result = redactor.redact(data);

            assertThat(result.get("tradeId")).isEqualTo("trade-123");
            assertThat(result.get("amount")).isEqualTo(1000);
            assertThat(result.get("currency")).isEqualTo("USD");
        }
    }

    @Nested
    @DisplayName("Case insensitivity")
    class CaseInsensitivity {

        @Test
        @DisplayName("should redact regardless of case")
        void shouldRedactRegardlessOfCase() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("PASSWORD", "secret1");
            data.put("Password", "secret2");
            data.put("passWORD", "secret3");

            Map<String, Object> result = redactor.redact(data);

            assertThat(result.get("PASSWORD")).isEqualTo(SensitiveDataRedactor.REDACTED);
            assertThat(result.get("Password")).isEqualTo(SensitiveDataRedactor.REDACTED);
            assertThat(result.get("passWORD")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }

        @Test
        @DisplayName("should match substrings (e.g., 'userPassword' contains 'password')")
        void shouldMatchSubstrings() {
            Map<String, Object> data = Map.of("userPassword", "my-pass");
            assertThat(redactor.redact(data).get("userPassword")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should return empty map for null input")
        void shouldReturnEmptyForNull() {
            assertThat(redactor.redact(null)).isEmpty();
        }

        @Test
        @DisplayName("should return empty map for empty input")
        void shouldReturnEmptyForEmpty() {
            assertThat(redactor.redact(Map.of())).isEmpty();
        }

        @Test
        @DisplayName("should handle mixed sensitive and non-sensitive fields")
        void shouldHandleMixedFields() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tradeId", "T-001");
            data.put("password", "s3cr3t");
            data.put("amount", 50000);
            data.put("apiKey", "KEY123");

            Map<String, Object> result = redactor.redact(data);

            assertThat(result).hasSize(4);
            assertThat(result.get("tradeId")).isEqualTo("T-001");
            assertThat(result.get("password")).isEqualTo(SensitiveDataRedactor.REDACTED);
            assertThat(result.get("amount")).isEqualTo(50000);
            assertThat(result.get("apiKey")).isEqualTo(SensitiveDataRedactor.REDACTED);
        }
    }

    @Nested
    @DisplayName("isSensitive")
    class IsSensitive {

        @Test
        @DisplayName("should return true for known sensitive fields")
        void shouldReturnTrueForSensitive() {
            assertThat(redactor.isSensitive("password")).isTrue();
            assertThat(redactor.isSensitive("token")).isTrue();
            assertThat(redactor.isSensitive("apiKey")).isTrue();
        }

        @Test
        @DisplayName("should return false for non-sensitive fields")
        void shouldReturnFalseForNonSensitive() {
            assertThat(redactor.isSensitive("tradeId")).isFalse();
            assertThat(redactor.isSensitive("amount")).isFalse();
        }

        @Test
        @DisplayName("should return false for null")
        void shouldReturnFalseForNull() {
            assertThat(redactor.isSensitive(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Custom patterns")
    class CustomPatterns {

        @Test
        @DisplayName("should use custom sensitive patterns")
        void shouldUseCustomPatterns() {
            var custom = new SensitiveDataRedactor(Set.of("ssn", "creditcard"));

            Map<String, Object> data = Map.of(
                    "ssn", "123-45-6789",
                    "creditcard", "4111-1111-1111-1111",
                    "password", "visible"  // 'password' is NOT in custom patterns
            );
            Map<String, Object> result = custom.redact(data);

            assertThat(result.get("ssn")).isEqualTo(SensitiveDataRedactor.REDACTED);
            assertThat(result.get("creditcard")).isEqualTo(SensitiveDataRedactor.REDACTED);
            assertThat(result.get("password")).isEqualTo("visible"); // Not redacted with custom patterns
        }

        @Test
        @DisplayName("should expose configured patterns")
        void shouldExposePatterns() {
            assertThat(redactor.sensitivePatterns()).contains("password", "token", "secret");
        }
    }
}
