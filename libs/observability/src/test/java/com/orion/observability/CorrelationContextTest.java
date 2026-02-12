package com.orion.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CorrelationContext} record — validates construction, validation rules, MDC key
 * constants, and immutability.
 */
@DisplayName("CorrelationContext")
class CorrelationContextTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create context with all fields populated")
        void shouldCreateWithAllFields() {
            var ctx =
                    new CorrelationContext(
                            "corr-001",
                            "tenant-001",
                            "user-001",
                            "req-001",
                            "span-abc",
                            "trace-xyz");

            assertThat(ctx.correlationId()).isEqualTo("corr-001");
            assertThat(ctx.tenantId()).isEqualTo("tenant-001");
            assertThat(ctx.userId()).isEqualTo("user-001");
            assertThat(ctx.requestId()).isEqualTo("req-001");
            assertThat(ctx.spanId()).isEqualTo("span-abc");
            assertThat(ctx.traceId()).isEqualTo("trace-xyz");
        }

        @Test
        @DisplayName("should allow nullable optional fields (userId, requestId, spanId, traceId)")
        void shouldAllowNullOptionalFields() {
            var ctx = new CorrelationContext("corr-001", "tenant-001", null, null, null, null);

            assertThat(ctx.correlationId()).isEqualTo("corr-001");
            assertThat(ctx.tenantId()).isEqualTo("tenant-001");
            assertThat(ctx.userId()).isNull();
            assertThat(ctx.requestId()).isNull();
            assertThat(ctx.spanId()).isNull();
            assertThat(ctx.traceId()).isNull();
        }

        @Test
        @DisplayName("should reject null correlationId")
        void shouldRejectNullCorrelationId() {
            assertThatThrownBy(() -> new CorrelationContext(null, "tenant", null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("correlationId");
        }

        @Test
        @DisplayName("should reject blank correlationId")
        void shouldRejectBlankCorrelationId() {
            assertThatThrownBy(() -> new CorrelationContext("  ", "tenant", null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("correlationId");
        }
    }

    @Nested
    @DisplayName("MDC key constants")
    class MdcKeys {

        @Test
        @DisplayName("should expose standard MDC key names")
        void shouldExposeStandardMdcKeys() {
            assertThat(CorrelationContext.MDC_CORRELATION_ID).isEqualTo("correlationId");
            assertThat(CorrelationContext.MDC_TENANT_ID).isEqualTo("tenantId");
            assertThat(CorrelationContext.MDC_USER_ID).isEqualTo("userId");
            assertThat(CorrelationContext.MDC_REQUEST_ID).isEqualTo("requestId");
            assertThat(CorrelationContext.MDC_SPAN_ID).isEqualTo("spanId");
            assertThat(CorrelationContext.MDC_TRACE_ID).isEqualTo("traceId");
        }
    }

    @Nested
    @DisplayName("Record equality")
    class Equality {

        @Test
        @DisplayName("should be equal when all fields match")
        void shouldBeEqualWhenAllFieldsMatch() {
            var ctx1 = new CorrelationContext("c1", "t1", "u1", "r1", "s1", "tr1");
            var ctx2 = new CorrelationContext("c1", "t1", "u1", "r1", "s1", "tr1");

            assertThat(ctx1).isEqualTo(ctx2);
            assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when correlationId differs")
        void shouldNotBeEqualWhenCorrelationIdDiffers() {
            var ctx1 = new CorrelationContext("c1", "t1", "u1", "r1", null, null);
            var ctx2 = new CorrelationContext("c2", "t1", "u1", "r1", null, null);

            assertThat(ctx1).isNotEqualTo(ctx2);
        }

        @Test
        @DisplayName("should have readable toString")
        void shouldHaveReadableToString() {
            var ctx = new CorrelationContext("corr-001", "tenant-001", null, null, null, null);
            assertThat(ctx.toString()).contains("corr-001").contains("tenant-001");
        }
    }
}
