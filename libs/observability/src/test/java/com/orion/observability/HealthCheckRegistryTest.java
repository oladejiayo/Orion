package com.orion.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orion.observability.testing.InMemoryHealthCheck;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HealthCheckRegistry} — validates registration, deregistration, concurrent check
 * execution, timeout handling, and overall status aggregation.
 */
@DisplayName("HealthCheckRegistry")
class HealthCheckRegistryTest {

    private HealthCheckRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new HealthCheckRegistry();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create registry with default timeout")
        void shouldCreateWithDefaultTimeout() {
            assertThat(registry.timeoutMs()).isEqualTo(HealthCheckRegistry.DEFAULT_TIMEOUT_MS);
        }

        @Test
        @DisplayName("should create registry with custom timeout")
        void shouldCreateWithCustomTimeout() {
            var custom = new HealthCheckRegistry(1000);
            assertThat(custom.timeoutMs()).isEqualTo(1000);
        }

        @Test
        @DisplayName("should reject non-positive timeout")
        void shouldRejectNonPositiveTimeout() {
            assertThatThrownBy(() -> new HealthCheckRegistry(0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new HealthCheckRegistry(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        @DisplayName("should register and count health checks")
        void shouldRegisterAndCount() {
            registry.register("postgres", new InMemoryHealthCheck("postgres"));
            registry.register("redis", new InMemoryHealthCheck("redis"));

            assertThat(registry.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("should replace existing check with same name")
        void shouldReplaceExisting() {
            var check1 = new InMemoryHealthCheck("postgres");
            var check2 = new InMemoryHealthCheck("postgres").setUnhealthy("new check");

            registry.register("postgres", check1);
            registry.register("postgres", check2);

            assertThat(registry.size()).isEqualTo(1);

            HealthResult result = registry.checkAll();
            assertThat(result.checks().get("postgres").status()).isEqualTo(HealthStatus.UNHEALTHY);
        }

        @Test
        @DisplayName("should reject null name")
        void shouldRejectNullName() {
            assertThatThrownBy(() -> registry.register(null, new InMemoryHealthCheck("test")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject null check")
        void shouldRejectNullCheck() {
            assertThatThrownBy(() -> registry.register("test", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should deregister check by name")
        void shouldDeregister() {
            registry.register("postgres", new InMemoryHealthCheck("postgres"));
            assertThat(registry.deregister("postgres")).isTrue();
            assertThat(registry.size()).isZero();
        }

        @Test
        @DisplayName("should return false when deregistering non-existent check")
        void shouldReturnFalseForNonExistent() {
            assertThat(registry.deregister("nonexistent")).isFalse();
        }
    }

    @Nested
    @DisplayName("checkAll — aggregation")
    class CheckAll {

        @Test
        @DisplayName("should return HEALTHY when no checks are registered")
        void shouldReturnHealthyWhenEmpty() {
            HealthResult result = registry.checkAll();

            assertThat(result.status()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(result.checks()).isEmpty();
            assertThat(result.timestamp()).isNotNull();
        }

        @Test
        @DisplayName("should return HEALTHY when all checks pass")
        void shouldReturnHealthyWhenAllPass() {
            registry.register("postgres", new InMemoryHealthCheck("postgres"));
            registry.register("redis", new InMemoryHealthCheck("redis"));
            registry.register("kafka", new InMemoryHealthCheck("kafka"));

            HealthResult result = registry.checkAll();

            assertThat(result.status()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(result.checks()).hasSize(3);
            assertThat(result.checks().values()).allMatch(h -> h.status() == HealthStatus.HEALTHY);
        }

        @Test
        @DisplayName("should return UNHEALTHY when any check fails")
        void shouldReturnUnhealthyWhenAnyFails() {
            registry.register("postgres", new InMemoryHealthCheck("postgres"));
            registry.register(
                    "redis", new InMemoryHealthCheck("redis").setUnhealthy("connection refused"));

            HealthResult result = registry.checkAll();

            assertThat(result.status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(result.checks().get("postgres").status()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(result.checks().get("redis").status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(result.checks().get("redis").message()).isEqualTo("connection refused");
        }

        @Test
        @DisplayName("should return DEGRADED when a check is degraded but none are unhealthy")
        void shouldReturnDegradedWhenDegraded() {
            registry.register("postgres", new InMemoryHealthCheck("postgres"));
            registry.register(
                    "redis", new InMemoryHealthCheck("redis").setDegraded("high latency"));

            HealthResult result = registry.checkAll();

            assertThat(result.status()).isEqualTo(HealthStatus.DEGRADED);
        }

        @Test
        @DisplayName("should return UNHEALTHY over DEGRADED")
        void shouldPreferUnhealthyOverDegraded() {
            registry.register("postgres", new InMemoryHealthCheck("postgres").setDegraded("slow"));
            registry.register("redis", new InMemoryHealthCheck("redis").setUnhealthy("down"));

            HealthResult result = registry.checkAll();

            assertThat(result.status()).isEqualTo(HealthStatus.UNHEALTHY);
        }

        @Test
        @DisplayName("should handle timeout by reporting UNHEALTHY")
        void shouldHandleTimeout() {
            var shortTimeoutRegistry = new HealthCheckRegistry(100); // 100ms timeout

            // Register a check that takes too long
            shortTimeoutRegistry.register(
                    "slow",
                    () ->
                            CompletableFuture.supplyAsync(
                                    () -> {
                                        try {
                                            Thread.sleep(5000); // 5 seconds — will timeout
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                        return ComponentHealth.healthy("slow", 5000);
                                    }));

            HealthResult result = shortTimeoutRegistry.checkAll();

            assertThat(result.status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(result.checks().get("slow").status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(result.checks().get("slow").message()).contains("Timeout");
        }

        @Test
        @DisplayName("should handle exception-throwing health check")
        void shouldHandleExceptionThrowingCheck() {
            registry.register(
                    "bad-check",
                    () -> CompletableFuture.failedFuture(new RuntimeException("check exploded")));

            HealthResult result = registry.checkAll();

            assertThat(result.status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(result.checks().get("bad-check").status()).isEqualTo(HealthStatus.UNHEALTHY);
        }

        @Test
        @DisplayName("should include timestamp in result")
        void shouldIncludeTimestamp() {
            registry.register("pg", new InMemoryHealthCheck("pg"));

            HealthResult result = registry.checkAll();
            assertThat(result.timestamp()).isNotNull();
        }
    }
}
