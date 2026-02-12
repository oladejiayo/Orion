package com.orion.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HealthStatus} enum.
 */
@DisplayName("HealthStatus")
class HealthStatusTest {

    @Test
    @DisplayName("should have three values: HEALTHY, DEGRADED, UNHEALTHY")
    void shouldHaveThreeValues() {
        assertThat(HealthStatus.values()).containsExactly(
                HealthStatus.HEALTHY,
                HealthStatus.DEGRADED,
                HealthStatus.UNHEALTHY
        );
    }

    @Test
    @DisplayName("should convert from string via valueOf")
    void shouldConvertFromString() {
        assertThat(HealthStatus.valueOf("HEALTHY")).isEqualTo(HealthStatus.HEALTHY);
        assertThat(HealthStatus.valueOf("DEGRADED")).isEqualTo(HealthStatus.DEGRADED);
        assertThat(HealthStatus.valueOf("UNHEALTHY")).isEqualTo(HealthStatus.UNHEALTHY);
    }
}
