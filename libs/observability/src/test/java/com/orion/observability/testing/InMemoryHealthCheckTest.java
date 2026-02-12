package com.orion.observability.testing;

import com.orion.observability.ComponentHealth;
import com.orion.observability.HealthStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InMemoryHealthCheck} — validates controllable health
 * status transitions and result reporting.
 */
@DisplayName("InMemoryHealthCheck")
class InMemoryHealthCheckTest {

    @Nested
    @DisplayName("Default state")
    class DefaultState {

        @Test
        @DisplayName("should start as HEALTHY")
        void shouldStartHealthy() throws ExecutionException, InterruptedException {
            var check = new InMemoryHealthCheck("postgres");
            ComponentHealth health = check.check().get();

            assertThat(health.name()).isEqualTo("postgres");
            assertThat(health.status()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(health.message()).isNull();
            assertThat(health.latencyMs()).isZero();
        }
    }

    @Nested
    @DisplayName("State transitions")
    class StateTransitions {

        @Test
        @DisplayName("should transition to UNHEALTHY")
        void shouldTransitionToUnhealthy() throws ExecutionException, InterruptedException {
            var check = new InMemoryHealthCheck("redis");
            check.setUnhealthy("connection refused");

            ComponentHealth health = check.check().get();
            assertThat(health.status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(health.message()).isEqualTo("connection refused");
        }

        @Test
        @DisplayName("should transition to DEGRADED")
        void shouldTransitionToDegraded() throws ExecutionException, InterruptedException {
            var check = new InMemoryHealthCheck("kafka");
            check.setDegraded("high latency");

            ComponentHealth health = check.check().get();
            assertThat(health.status()).isEqualTo(HealthStatus.DEGRADED);
            assertThat(health.message()).isEqualTo("high latency");
        }

        @Test
        @DisplayName("should transition back to HEALTHY")
        void shouldTransitionBackToHealthy() throws ExecutionException, InterruptedException {
            var check = new InMemoryHealthCheck("postgres");
            check.setUnhealthy("down");
            check.setHealthy();

            ComponentHealth health = check.check().get();
            assertThat(health.status()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(health.message()).isNull();
        }

        @Test
        @DisplayName("should support fluent API")
        void shouldSupportFluentApi() throws ExecutionException, InterruptedException {
            var check = new InMemoryHealthCheck("redis")
                    .setUnhealthy("timeout")
                    .setLatencyMs(500);

            ComponentHealth health = check.check().get();
            assertThat(health.status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(health.latencyMs()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("Latency simulation")
    class LatencySimulation {

        @Test
        @DisplayName("should report configured latency")
        void shouldReportConfiguredLatency() throws ExecutionException, InterruptedException {
            var check = new InMemoryHealthCheck("postgres").setLatencyMs(42);

            ComponentHealth health = check.check().get();
            assertThat(health.latencyMs()).isEqualTo(42);
        }
    }

    @Nested
    @DisplayName("Component name")
    class ComponentName {

        @Test
        @DisplayName("should expose component name")
        void shouldExposeComponentName() {
            var check = new InMemoryHealthCheck("my-component");
            assertThat(check.componentName()).isEqualTo("my-component");
        }
    }
}
