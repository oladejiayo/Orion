package com.orion.observability;

import org.slf4j.MDC;

import java.util.Optional;

/**
 * Thread-local holder for {@link CorrelationContext} with SLF4J MDC bridge.
 * <p>
 * When a correlation context is set, all MDC keys (correlationId, tenantId, userId,
 * requestId, spanId, traceId) are populated so that every log statement on this thread
 * automatically includes them. When cleared, all MDC keys are removed.
 * <p>
 * For virtual threads (Java 21), each virtual thread gets its own ThreadLocal copy.
 * When using thread pools, callers must transfer the context explicitly or use
 * {@link #runWithContext(CorrelationContext, Runnable)}.
 */
public final class CorrelationContextHolder {

    private static final ThreadLocal<CorrelationContext> CONTEXT = new ThreadLocal<>();

    private CorrelationContextHolder() {
        // Utility class — no instantiation
    }

    /**
     * Sets the correlation context for the current thread and populates SLF4J MDC.
     *
     * @param context the correlation context to set (must not be null)
     * @throws IllegalArgumentException if context is null
     */
    public static void set(CorrelationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        CONTEXT.set(context);
        populateMdc(context);
    }

    /**
     * Returns the current thread's correlation context, if set.
     */
    public static Optional<CorrelationContext> get() {
        return Optional.ofNullable(CONTEXT.get());
    }

    /**
     * Clears the correlation context and removes all MDC keys for the current thread.
     */
    public static void clear() {
        CONTEXT.remove();
        clearMdc();
    }

    /**
     * Executes a {@link Runnable} with the given correlation context set, then restores
     * the previous context (or clears if there was none). Useful for thread pool handoff.
     *
     * @param context the correlation context for the duration of the runnable
     * @param runnable the work to execute
     */
    public static void runWithContext(CorrelationContext context, Runnable runnable) {
        CorrelationContext previous = CONTEXT.get();
        try {
            set(context);
            runnable.run();
        } finally {
            if (previous != null) {
                set(previous);
            } else {
                clear();
            }
        }
    }

    private static void populateMdc(CorrelationContext ctx) {
        setMdc(CorrelationContext.MDC_CORRELATION_ID, ctx.correlationId());
        setMdc(CorrelationContext.MDC_TENANT_ID, ctx.tenantId());
        setMdc(CorrelationContext.MDC_USER_ID, ctx.userId());
        setMdc(CorrelationContext.MDC_REQUEST_ID, ctx.requestId());
        setMdc(CorrelationContext.MDC_SPAN_ID, ctx.spanId());
        setMdc(CorrelationContext.MDC_TRACE_ID, ctx.traceId());
    }

    private static void setMdc(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        } else {
            MDC.remove(key);
        }
    }

    private static void clearMdc() {
        MDC.remove(CorrelationContext.MDC_CORRELATION_ID);
        MDC.remove(CorrelationContext.MDC_TENANT_ID);
        MDC.remove(CorrelationContext.MDC_USER_ID);
        MDC.remove(CorrelationContext.MDC_REQUEST_ID);
        MDC.remove(CorrelationContext.MDC_SPAN_ID);
        MDC.remove(CorrelationContext.MDC_TRACE_ID);
    }
}
