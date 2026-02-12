package com.orion.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Factory for creating Micrometer metrics with automatic tenant label inclusion.
 * <p>
 * Every metric created through this factory automatically includes a {@code tenant} tag
 * sourced from the current {@link CorrelationContextHolder}. Additional tags can be
 * supplied per metric.
 * <p>
 * This class wraps {@link MeterRegistry} to enforce consistent metric naming and
 * tenant-level segmentation across all Orion services.
 */
public final class MetricFactory {

    /** Tag key for tenant segmentation. */
    public static final String TAG_TENANT = "tenant";

    /** Tag key for service name. */
    public static final String TAG_SERVICE = "service";

    private final MeterRegistry registry;
    private final String serviceName;

    /**
     * Creates a MetricFactory bound to the given registry and service name.
     *
     * @param registry    the Micrometer meter registry (e.g., PrometheusMeterRegistry)
     * @param serviceName logical service name included as a default tag
     */
    public MetricFactory(MeterRegistry registry, String serviceName) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be null or blank");
        }
        this.registry = registry;
        this.serviceName = serviceName;
    }

    /**
     * Creates a counter with automatic service and tenant tags.
     *
     * @param name        metric name (e.g., "orders.created")
     * @param description human-readable description
     * @param tags        additional tags (key-value pairs)
     * @return the counter
     */
    public Counter counter(String name, String description, String... tags) {
        return Counter.builder(name)
                .description(description)
                .tags(baseTags(tags))
                .register(registry);
    }

    /**
     * Creates a timer (histogram of durations) with automatic service and tenant tags.
     *
     * @param name        metric name (e.g., "request.duration")
     * @param description human-readable description
     * @param tags        additional tags (key-value pairs)
     * @return the timer
     */
    public Timer timer(String name, String description, String... tags) {
        return Timer.builder(name)
                .description(description)
                .tags(baseTags(tags))
                .register(registry);
    }

    /**
     * Creates a distribution summary with automatic service and tenant tags.
     *
     * @param name        metric name (e.g., "payload.size")
     * @param description human-readable description
     * @param tags        additional tags (key-value pairs)
     * @return the distribution summary
     */
    public DistributionSummary distributionSummary(String name, String description, String... tags) {
        return DistributionSummary.builder(name)
                .description(description)
                .tags(baseTags(tags))
                .register(registry);
    }

    /**
     * Registers a gauge backed by a supplier, with automatic service and tenant tags.
     *
     * @param name        metric name (e.g., "connections.active")
     * @param description human-readable description
     * @param supplier    supplier for the gauge value
     * @param tags        additional tags (key-value pairs)
     * @return an AtomicLong that can be used to update the gauge value
     */
    public AtomicLong gauge(String name, String description, String... tags) {
        AtomicLong value = new AtomicLong(0);
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description)
                .tags(baseTags(tags))
                .register(registry);
        return value;
    }

    /**
     * Returns the underlying meter registry.
     */
    public MeterRegistry registry() {
        return registry;
    }

    /**
     * Returns the service name used as a default tag.
     */
    public String serviceName() {
        return serviceName;
    }

    /**
     * Combines service tag with any additional tags. The tenant tag is added as a
     * common tag so it appears on every metric from this service.
     */
    private Tags baseTags(String... extraTags) {
        Tags tags = Tags.of(TAG_SERVICE, serviceName);
        if (extraTags.length > 0) {
            tags = tags.and(extraTags);
        }
        return tags;
    }
}
