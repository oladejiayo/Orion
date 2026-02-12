package com.orion.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for BearerTokenExtractor (AC1: JWT Token Handling — token extraction).
 *
 * WHY: Verify correct extraction of JWT from "Bearer xxx" Authorization header,
 * and graceful handling of missing/malformed headers.
 */
@DisplayName("US-01-04 AC1: BearerTokenExtractor")
class BearerTokenExtractorTest {

    @Nested
    @DisplayName("valid headers")
    class ValidHeaders {

        @Test
        @DisplayName("extracts token from 'Bearer xxx' header")
        void extractsToken() {
            var result = BearerTokenExtractor.extract("Bearer eyJhbGciOiJSUzI1NiJ9.payload.sig");
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("eyJhbGciOiJSUzI1NiJ9.payload.sig");
        }

        @Test
        @DisplayName("handles extra whitespace after Bearer")
        void handlesExtraWhitespace() {
            var result = BearerTokenExtractor.extract("Bearer   my-token");
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("my-token");
        }
    }

    @Nested
    @DisplayName("invalid headers")
    class InvalidHeaders {

        @Test
        @DisplayName("returns empty for null header")
        void nullHeader() {
            assertThat(BearerTokenExtractor.extract(null)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for empty string")
        void emptyHeader() {
            assertThat(BearerTokenExtractor.extract("")).isEmpty();
        }

        @Test
        @DisplayName("returns empty for 'Basic' auth scheme")
        void basicScheme() {
            assertThat(BearerTokenExtractor.extract("Basic dXNlcjpwYXNz")).isEmpty();
        }

        @Test
        @DisplayName("returns empty for 'Bearer' with no token")
        void bearerNoToken() {
            assertThat(BearerTokenExtractor.extract("Bearer ")).isEmpty();
            assertThat(BearerTokenExtractor.extract("Bearer")).isEmpty();
        }

        @Test
        @DisplayName("is case-insensitive for 'Bearer' prefix")
        void caseInsensitive() {
            var result = BearerTokenExtractor.extract("bearer my-token");
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("my-token");
        }
    }
}
