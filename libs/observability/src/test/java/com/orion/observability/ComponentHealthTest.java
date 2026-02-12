package com.orion.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ComponentHealth} record — validates factory methods, field access, and equality.
 */
@DisplayName("ComponentHealth")
class ComponentHealthTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("healthy() should create HEALTHY component with no message")
        void shouldCreateHealthy() {
            ComponentHealth health = ComponentHealth.healthy("postgres", 12);

            assertThat(health.name()).isEqualTo("postgres");
            assertThat(health.status()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(health.message()).isNull();
            assertThat(health.latencyMs()).isEqualTo(12);
        }

        @Test
        @DisplayName("unhealthy() should create UNHEALTHY component with message")
        void shouldCreateUnhealthy() {
            ComponentHealth health = ComponentHealth.unhealthy("redis", "Connection refused", 5000);

            assertThat(health.name()).isEqualTo("redis");
            assertThat(health.status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(health.message()).isEqualTo("Connection refused");
            assertThat(health.latencyMs()).isEqualTo(5000);
        }
    }

    @Nested
    @DisplayName("Record equality")
    class Equality {

        @Test
        @DisplayName("should be equal when all fields match")
        void shouldBeEqual() {
            var h1 = ComponentHealth.healthy("pg", 10);
            var h2 = ComponentHealth.healthy("pg", 10);

            assertThat(h1).isEqualTo(h2);
            assertThat(h1.hashCode()).isEqualTo(h2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when status differs")
        void shouldNotBeEqualWhenStatusDiffers() {
            var h1 = ComponentHealth.healthy("pg", 10);
            var h2 = ComponentHealth.unhealthy("pg", "down", 10);

            assertThat(h1).isNotEqualTo(h2);
        }
    }
}
