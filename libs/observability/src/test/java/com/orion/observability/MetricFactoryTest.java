package com.orion.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MetricFactory} — validates metric creation with automatic service tags,
 * counter/timer/gauge/distribution operations.
 */
@DisplayName("MetricFactory")
class MetricFactoryTest {

    private SimpleMeterRegistry registry;
    private MetricFactory factory;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        factory = new MetricFactory(registry, "test-service");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should reject null registry")
        void shouldRejectNullRegistry() {
            assertThatThrownBy(() -> new MetricFactory(null, "svc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("registry");
        }

        @Test
        @DisplayName("should reject null service name")
        void shouldRejectNullServiceName() {
            assertThatThrownBy(() -> new MetricFactory(registry, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("serviceName");
        }

        @Test
        @DisplayName("should reject blank service name")
        void shouldRejectBlankServiceName() {
            assertThatThrownBy(() -> new MetricFactory(registry, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("serviceName");
        }

        @Test
        @DisplayName("should expose registry and service name")
        void shouldExposeRegistryAndServiceName() {
            assertThat(factory.registry()).isSameAs(registry);
            assertThat(factory.serviceName()).isEqualTo("test-service");
        }
    }

    @Nested
    @DisplayName("Counter")
    class CounterTests {

        @Test
        @DisplayName("should create counter with service tag")
        void shouldCreateCounterWithServiceTag() {
            Counter counter = factory.counter("orders.created", "Number of orders created");

            counter.increment();
            counter.increment(5);

            assertThat(counter.count()).isEqualTo(6.0);
            assertThat(counter.getId().getTag("service")).isEqualTo("test-service");
        }

        @Test
        @DisplayName("should create counter with additional tags")
        void shouldCreateCounterWithExtraTags() {
            Counter counter =
                    factory.counter("trades.executed", "Trades executed", "asset_class", "FX");

            counter.increment();

            assertThat(counter.getId().getTag("service")).isEqualTo("test-service");
            assertThat(counter.getId().getTag("asset_class")).isEqualTo("FX");
        }
    }

    @Nested
    @DisplayName("Timer")
    class TimerTests {

        @Test
        @DisplayName("should create timer with service tag")
        void shouldCreateTimerWithServiceTag() {
            Timer timer = factory.timer("request.duration", "Request duration");

            timer.record(Duration.ofMillis(150));
            timer.record(Duration.ofMillis(250));

            assertThat(timer.count()).isEqualTo(2);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isEqualTo(400.0);
            assertThat(timer.getId().getTag("service")).isEqualTo("test-service");
        }

        @Test
        @DisplayName("should create timer with additional tags")
        void shouldCreateTimerWithExtraTags() {
            Timer timer =
                    factory.timer("grpc.latency", "gRPC call latency", "method", "PlaceOrder");

            assertThat(timer.getId().getTag("method")).isEqualTo("PlaceOrder");
            assertThat(timer.getId().getTag("service")).isEqualTo("test-service");
        }
    }

    @Nested
    @DisplayName("DistributionSummary")
    class DistributionSummaryTests {

        @Test
        @DisplayName("should create distribution summary with service tag")
        void shouldCreateDistributionSummary() {
            DistributionSummary summary =
                    factory.distributionSummary("payload.size", "Request payload size in bytes");

            summary.record(1024);
            summary.record(2048);

            assertThat(summary.count()).isEqualTo(2);
            assertThat(summary.totalAmount()).isEqualTo(3072.0);
            assertThat(summary.getId().getTag("service")).isEqualTo("test-service");
        }
    }

    @Nested
    @DisplayName("Gauge")
    class GaugeTests {

        @Test
        @DisplayName("should create gauge backed by AtomicLong with service tag")
        void shouldCreateGaugeWithServiceTag() {
            AtomicLong value = factory.gauge("connections.active", "Active connections");

            value.set(42);

            // Verify the gauge is registered and has the right tags
            assertThat(
                            registry.get("connections.active")
                                    .tag("service", "test-service")
                                    .gauge()
                                    .value())
                    .isEqualTo(42.0);
        }

        @Test
        @DisplayName("should update gauge value dynamically")
        void shouldUpdateGaugeDynamically() {
            AtomicLong value = factory.gauge("queue.depth", "Queue depth");

            value.set(10);
            assertThat(registry.get("queue.depth").gauge().value()).isEqualTo(10.0);

            value.set(25);
            assertThat(registry.get("queue.depth").gauge().value()).isEqualTo(25.0);
        }
    }
}
