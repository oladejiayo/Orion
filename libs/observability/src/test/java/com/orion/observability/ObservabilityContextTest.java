package com.orion.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ObservabilityContext} record — validates construction
 * and required field validation.
 */
@DisplayName("ObservabilityContext")
class ObservabilityContextTest {

    private final CorrelationContext correlation = new CorrelationContext(
            "corr-001", "tenant-001", "user-001", "req-001", null, null);

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create context with all fields")
        void shouldCreateWithAllFields() {
            var ctx = new ObservabilityContext("rfq-service", "1.0.0", "production", correlation);

            assertThat(ctx.serviceName()).isEqualTo("rfq-service");
            assertThat(ctx.serviceVersion()).isEqualTo("1.0.0");
            assertThat(ctx.environment()).isEqualTo("production");
            assertThat(ctx.correlation()).isEqualTo(correlation);
        }

        @Test
        @DisplayName("should allow nullable serviceVersion and environment")
        void shouldAllowNullableOptionalFields() {
            var ctx = new ObservabilityContext("rfq-service", null, null, correlation);

            assertThat(ctx.serviceVersion()).isNull();
            assertThat(ctx.environment()).isNull();
        }

        @Test
        @DisplayName("should reject null serviceName")
        void shouldRejectNullServiceName() {
            assertThatThrownBy(() -> new ObservabilityContext(null, "1.0", "prod", correlation))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("serviceName");
        }

        @Test
        @DisplayName("should reject blank serviceName")
        void shouldRejectBlankServiceName() {
            assertThatThrownBy(() -> new ObservabilityContext("  ", "1.0", "prod", correlation))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("serviceName");
        }

        @Test
        @DisplayName("should reject null correlation")
        void shouldRejectNullCorrelation() {
            assertThatThrownBy(() -> new ObservabilityContext("rfq-service", "1.0", "prod", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("correlation");
        }
    }

    @Nested
    @DisplayName("MDC key constants")
    class MdcKeys {

        @Test
        @DisplayName("should expose service-level MDC key names")
        void shouldExposeServiceMdcKeys() {
            assertThat(ObservabilityContext.MDC_SERVICE_NAME).isEqualTo("serviceName");
            assertThat(ObservabilityContext.MDC_SERVICE_VERSION).isEqualTo("serviceVersion");
            assertThat(ObservabilityContext.MDC_ENVIRONMENT).isEqualTo("environment");
        }
    }
}
