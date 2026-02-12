package com.orion.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for {@link HealthResult} record. */
@DisplayName("HealthResult")
class HealthResultTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create result with all fields")
        void shouldCreateWithAllFields() {
            var now = Instant.now();
            var pg = ComponentHealth.healthy("postgres", 5);
            var redis = ComponentHealth.unhealthy("redis", "timeout", 3000);

            var result =
                    new HealthResult(
                            HealthStatus.UNHEALTHY, Map.of("postgres", pg, "redis", redis), now);

            assertThat(result.status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(result.checks()).hasSize(2);
            assertThat(result.checks().get("postgres").status()).isEqualTo(HealthStatus.HEALTHY);
            assertThat(result.checks().get("redis").status()).isEqualTo(HealthStatus.UNHEALTHY);
            assertThat(result.timestamp()).isEqualTo(now);
        }

        @Test
        @DisplayName("should create immutable copy of checks map")
        void shouldCreateImmutableChecksMap() {
            var mutableMap = new java.util.HashMap<String, ComponentHealth>();
            mutableMap.put("pg", ComponentHealth.healthy("pg", 1));

            var result = new HealthResult(HealthStatus.HEALTHY, mutableMap, Instant.now());

            // Mutating the original map should not affect the result
            mutableMap.put("redis", ComponentHealth.healthy("redis", 2));
            assertThat(result.checks()).hasSize(1);
        }
    }
}
