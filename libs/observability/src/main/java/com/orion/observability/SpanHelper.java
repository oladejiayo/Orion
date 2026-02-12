package com.orion.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Convenience wrapper around OpenTelemetry {@link Tracer} that automatically
 * attaches correlation context attributes to every span.
 * <p>
 * This helper is intentionally thin — it wraps the OTel API but does NOT
 * configure the SDK. Services must configure the OpenTelemetry SDK (exporter,
 * sampler, resource attributes) at boot time.
 */
public final class SpanHelper {

    private final Tracer tracer;

    /**
     * Creates a SpanHelper backed by the given OTel tracer.
     *
     * @param tracer the OpenTelemetry tracer (typically obtained from {@code GlobalOpenTelemetry})
     */
    public SpanHelper(Tracer tracer) {
        if (tracer == null) {
            throw new IllegalArgumentException("tracer must not be null");
        }
        this.tracer = tracer;
    }

    /**
     * Executes a {@link Callable} within a new span. The span is ended automatically
     * and correlation context attributes are attached from the current
     * {@link CorrelationContextHolder}.
     *
     * @param spanName   name for the span
     * @param callable   the work to execute within the span
     * @param <T>        return type
     * @return the result of the callable
     * @throws Exception if the callable throws
     */
    public <T> T withSpan(String spanName, Callable<T> callable) throws Exception {
        return withSpan(spanName, SpanKind.INTERNAL, Map.of(), callable);
    }

    /**
     * Executes a {@link Callable} within a new span with explicit kind and attributes.
     *
     * @param spanName   name for the span
     * @param kind       span kind (INTERNAL, SERVER, CLIENT, PRODUCER, CONSUMER)
     * @param attributes additional span attributes
     * @param callable   the work to execute within the span
     * @param <T>        return type
     * @return the result of the callable
     * @throws Exception if the callable throws
     */
    public <T> T withSpan(String spanName, SpanKind kind, Map<String, String> attributes,
                          Callable<T> callable) throws Exception {
        var spanBuilder = tracer.spanBuilder(spanName).setSpanKind(kind);

        // Attach custom attributes
        attributes.forEach(spanBuilder::setAttribute);

        Span span = spanBuilder.startSpan();

        // Attach correlation context from current thread
        CorrelationContextHolder.get().ifPresent(ctx -> {
            span.setAttribute("correlation.id", ctx.correlationId());
            if (ctx.tenantId() != null) {
                span.setAttribute("tenant.id", ctx.tenantId());
            }
            if (ctx.userId() != null) {
                span.setAttribute("user.id", ctx.userId());
            }
        });

        try (Scope ignored = span.makeCurrent()) {
            T result = callable.call();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Executes a {@link Runnable} within a new span (void variant).
     *
     * @param spanName name for the span
     * @param runnable the work to execute within the span
     */
    public void withSpan(String spanName, Runnable runnable) {
        try {
            withSpan(spanName, () -> {
                runnable.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Unexpected checked exception in span", e);
        }
    }

    /**
     * Returns the underlying OTel tracer.
     */
    public Tracer tracer() {
        return tracer;
    }
}
