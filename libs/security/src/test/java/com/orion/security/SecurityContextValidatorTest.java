package com.orion.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for SecurityContextValidator (AC8: validation of security context).
 *
 * <p>WHY: Verify that validation catches missing required fields and returns all errors at once.
 */
@DisplayName("US-01-04 AC8: SecurityContextValidator")
class SecurityContextValidatorTest {

    @Nested
    @DisplayName("valid contexts")
    class ValidContexts {

        @Test
        @DisplayName("factory-created context passes validation")
        void factoryContextValid() {
            var ctx =
                    new OrionSecurityContext(
                            new AuthenticatedUser("u-1", "a@b.com", "auser", "Alice"),
                            new TenantContext("t-1", "Acme", TenantType.STANDARD),
                            List.of(Role.TRADER),
                            Entitlements.defaults(),
                            "token",
                            "corr-1");
            var result = SecurityContextValidator.validate(ctx);
            assertThat(result.valid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("missing fields")
    class MissingFields {

        @Test
        @DisplayName("null user fails")
        void nullUser() {
            var ctx =
                    new OrionSecurityContext(
                            null,
                            new TenantContext("t-1", "Acme", TenantType.STANDARD),
                            List.of(Role.TRADER),
                            Entitlements.defaults(),
                            "tok",
                            "c");
            var result = SecurityContextValidator.validate(ctx);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("user"));
        }

        @Test
        @DisplayName("blank userId fails")
        void blankUserId() {
            var ctx =
                    new OrionSecurityContext(
                            new AuthenticatedUser("", "a@b.com", "auser", "Alice"),
                            new TenantContext("t-1", "Acme", TenantType.STANDARD),
                            List.of(Role.TRADER),
                            Entitlements.defaults(),
                            "tok",
                            "c");
            var result = SecurityContextValidator.validate(ctx);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("userid"));
        }

        @Test
        @DisplayName("null tenant fails")
        void nullTenant() {
            var ctx =
                    new OrionSecurityContext(
                            new AuthenticatedUser("u", "a@b.com", "a", "A"),
                            null,
                            List.of(Role.TRADER),
                            Entitlements.defaults(),
                            "tok",
                            "c");
            var result = SecurityContextValidator.validate(ctx);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("tenant"));
        }

        @Test
        @DisplayName("blank tenantId fails")
        void blankTenantId() {
            var ctx =
                    new OrionSecurityContext(
                            new AuthenticatedUser("u", "a@b.com", "a", "A"),
                            new TenantContext("", "Acme", TenantType.STANDARD),
                            List.of(Role.TRADER),
                            Entitlements.defaults(),
                            "tok",
                            "c");
            var result = SecurityContextValidator.validate(ctx);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("tenantid"));
        }

        @Test
        @DisplayName("empty roles list fails")
        void emptyRoles() {
            var ctx =
                    new OrionSecurityContext(
                            new AuthenticatedUser("u", "a@b.com", "a", "A"),
                            new TenantContext("t", "Acme", TenantType.STANDARD),
                            List.of(),
                            Entitlements.defaults(),
                            "tok",
                            "c");
            var result = SecurityContextValidator.validate(ctx);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("role"));
        }

        @Test
        @DisplayName("null entitlements fails")
        void nullEntitlements() {
            var ctx =
                    new OrionSecurityContext(
                            new AuthenticatedUser("u", "a@b.com", "a", "A"),
                            new TenantContext("t", "Acme", TenantType.STANDARD),
                            List.of(Role.TRADER),
                            null,
                            "tok",
                            "c");
            var result = SecurityContextValidator.validate(ctx);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.toLowerCase().contains("entitlement"));
        }
    }

    @Nested
    @DisplayName("multiple errors")
    class MultipleErrors {

        @Test
        @DisplayName("reports ALL errors, not just the first one")
        void reportsAllErrors() {
            var ctx = new OrionSecurityContext(null, null, List.of(), null, null, null);
            var result = SecurityContextValidator.validate(ctx);
            assertThat(result.valid()).isFalse();
            assertThat(result.errors().size()).isGreaterThanOrEqualTo(4);
        }
    }
}
