package com.orion.servicetemplate.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.orion.observability.CorrelationContext;
import com.orion.observability.CorrelationContextHolder;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link CorrelationIdFilter}.
 *
 * <p>WHY: The correlation filter is the backbone of distributed tracing. Every HTTP request must
 * have a correlation ID — either propagated from the client or generated fresh. These tests verify
 * the filter's contract without starting a Spring context (pure servlet mock tests).
 */
@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void cleanup() {
        // WHY: Ensure no cross-test contamination via ThreadLocal
        CorrelationContextHolder.clear();
    }

    @Test
    @DisplayName("generates correlation ID when none provided")
    void generatesCorrelationIdWhenNoneProvided() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {};

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Correlation-ID")).isNotBlank();
    }

    @Test
    @DisplayName("propagates existing correlation ID from header")
    void propagatesExistingCorrelationId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "test-abc-123");
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {};

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo("test-abc-123");
    }

    @Test
    @DisplayName("sets CorrelationContextHolder during filter chain execution")
    void setsCorrelationContextDuringFilterChain() throws Exception {
        var capturedId = new AtomicReference<String>();

        // WHY: The filter chain lambda captures the context during execution,
        // proving the ThreadLocal is populated while the chain runs.
        FilterChain capturingChain =
                (req, resp) ->
                        capturedId.set(
                                CorrelationContextHolder.get()
                                        .map(CorrelationContext::correlationId)
                                        .orElse(null));

        var request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-ID", "during-chain-123");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain);

        assertThat(capturedId.get()).isEqualTo("during-chain-123");
    }

    @Test
    @DisplayName("clears CorrelationContextHolder after request completes")
    void clearsCorrelationContextAfterRequest() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {};

        filter.doFilter(request, response, chain);

        // WHY: If the ThreadLocal isn't cleared, Tomcat's thread pool
        // would leak correlation IDs between unrelated requests.
        assertThat(CorrelationContextHolder.get()).isEmpty();
    }
}
