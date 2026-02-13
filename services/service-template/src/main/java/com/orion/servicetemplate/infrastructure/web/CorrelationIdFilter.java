package com.orion.servicetemplate.infrastructure.web;

import com.orion.observability.CorrelationContext;
import com.orion.observability.CorrelationContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter that propagates or generates a correlation ID for every HTTP request.
 *
 * <p>WHY: Replaces Express correlationMiddleware from the TypeScript story. Every request gets a
 * unique correlation ID that flows through:
 *
 * <ol>
 *   <li>HTTP request header → this filter → {@link CorrelationContextHolder}
 *   <li>CorrelationContextHolder → SLF4J MDC → structured log output
 *   <li>CorrelationContextHolder → downstream HTTP calls / Kafka messages
 *   <li>This filter → HTTP response header (for client-side correlation)
 * </ol>
 *
 * <p>If the client sends {@code X-Correlation-ID}, we propagate it. If absent, we generate a new
 * UUID. This enables end-to-end request tracing across the entire Orion platform.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so correlation is available to all subsequent
 * filters and handlers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // WHY: CorrelationContext from orion-observability populates SLF4J MDC
        // automatically, so all log lines include the correlation ID.
        var context = new CorrelationContext(correlationId, null, null, null, null, null);
        CorrelationContextHolder.set(context);

        // WHY: Echo correlation ID back so clients can reference it in support requests.
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // WHY: ThreadLocal MUST be cleared to prevent memory leaks
            // and cross-request contamination (Tomcat reuses threads).
            CorrelationContextHolder.clear();
        }
    }
}
