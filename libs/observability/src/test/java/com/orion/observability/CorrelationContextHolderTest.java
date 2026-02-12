package com.orion.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CorrelationContextHolder} — validates ThreadLocal storage,
 * MDC bridge, context clearing, and scoped execution.
 */
@DisplayName("CorrelationContextHolder")
class CorrelationContextHolderTest {

    @AfterEach
    void cleanup() {
        // Ensure no context leaks between tests
        CorrelationContextHolder.clear();
    }

    @Nested
    @DisplayName("set/get/clear lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("should return empty when no context is set")
        void shouldReturnEmptyWhenNoContext() {
            assertThat(CorrelationContextHolder.get()).isEmpty();
        }

        @Test
        @DisplayName("should store and retrieve context")
        void shouldStoreAndRetrieveContext() {
            var ctx = new CorrelationContext("corr-1", "tenant-1", "user-1", "req-1", null, null);
            CorrelationContextHolder.set(ctx);

            assertThat(CorrelationContextHolder.get()).isPresent().contains(ctx);
        }

        @Test
        @DisplayName("should clear context")
        void shouldClearContext() {
            var ctx = new CorrelationContext("corr-1", "tenant-1", null, null, null, null);
            CorrelationContextHolder.set(ctx);
            CorrelationContextHolder.clear();

            assertThat(CorrelationContextHolder.get()).isEmpty();
        }

        @Test
        @DisplayName("should reject null context")
        void shouldRejectNullContext() {
            assertThatThrownBy(() -> CorrelationContextHolder.set(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("context");
        }
    }

    @Nested
    @DisplayName("MDC bridge")
    class MdcBridge {

        @Test
        @DisplayName("should populate MDC keys when context is set")
        void shouldPopulateMdcOnSet() {
            var ctx = new CorrelationContext("corr-1", "tenant-1", "user-1", "req-1", "span-1", "trace-1");
            CorrelationContextHolder.set(ctx);

            assertThat(MDC.get("correlationId")).isEqualTo("corr-1");
            assertThat(MDC.get("tenantId")).isEqualTo("tenant-1");
            assertThat(MDC.get("userId")).isEqualTo("user-1");
            assertThat(MDC.get("requestId")).isEqualTo("req-1");
            assertThat(MDC.get("spanId")).isEqualTo("span-1");
            assertThat(MDC.get("traceId")).isEqualTo("trace-1");
        }

        @Test
        @DisplayName("should clear MDC keys when context is cleared")
        void shouldClearMdcOnClear() {
            var ctx = new CorrelationContext("corr-1", "tenant-1", "user-1", "req-1", null, null);
            CorrelationContextHolder.set(ctx);
            CorrelationContextHolder.clear();

            assertThat(MDC.get("correlationId")).isNull();
            assertThat(MDC.get("tenantId")).isNull();
            assertThat(MDC.get("userId")).isNull();
            assertThat(MDC.get("requestId")).isNull();
        }

        @Test
        @DisplayName("should not set MDC for null optional fields")
        void shouldNotSetMdcForNullFields() {
            var ctx = new CorrelationContext("corr-1", "tenant-1", null, null, null, null);
            CorrelationContextHolder.set(ctx);

            assertThat(MDC.get("correlationId")).isEqualTo("corr-1");
            assertThat(MDC.get("tenantId")).isEqualTo("tenant-1");
            assertThat(MDC.get("userId")).isNull();
            assertThat(MDC.get("requestId")).isNull();
            assertThat(MDC.get("spanId")).isNull();
            assertThat(MDC.get("traceId")).isNull();
        }
    }

    @Nested
    @DisplayName("runWithContext")
    class RunWithContext {

        @Test
        @DisplayName("should set context for the duration of the runnable and restore afterwards")
        void shouldSetContextAndRestore() {
            var outer = new CorrelationContext("outer-corr", "tenant-1", null, null, null, null);
            var inner = new CorrelationContext("inner-corr", "tenant-2", null, null, null, null);

            CorrelationContextHolder.set(outer);

            AtomicReference<String> capturedCorrelationId = new AtomicReference<>();
            CorrelationContextHolder.runWithContext(inner, () -> {
                capturedCorrelationId.set(
                        CorrelationContextHolder.get().map(CorrelationContext::correlationId).orElse(null));
            });

            // Inner context was active during runnable
            assertThat(capturedCorrelationId.get()).isEqualTo("inner-corr");

            // Outer context is restored
            assertThat(CorrelationContextHolder.get()).isPresent();
            assertThat(CorrelationContextHolder.get().get().correlationId()).isEqualTo("outer-corr");
        }

        @Test
        @DisplayName("should clear context after runnable when no previous context existed")
        void shouldClearWhenNoPreviousContext() {
            var ctx = new CorrelationContext("temp-corr", "tenant-1", null, null, null, null);

            CorrelationContextHolder.runWithContext(ctx, () -> {
                assertThat(CorrelationContextHolder.get()).isPresent();
            });

            assertThat(CorrelationContextHolder.get()).isEmpty();
        }

        @Test
        @DisplayName("should restore context even if runnable throws")
        void shouldRestoreOnException() {
            var outer = new CorrelationContext("outer-corr", "tenant-1", null, null, null, null);
            var inner = new CorrelationContext("inner-corr", "tenant-2", null, null, null, null);

            CorrelationContextHolder.set(outer);

            try {
                CorrelationContextHolder.runWithContext(inner, () -> {
                    throw new RuntimeException("boom");
                });
            } catch (RuntimeException ignored) {
                // Expected
            }

            // Outer context is restored despite exception
            assertThat(CorrelationContextHolder.get()).isPresent();
            assertThat(CorrelationContextHolder.get().get().correlationId()).isEqualTo("outer-corr");
        }
    }

    @Nested
    @DisplayName("Thread isolation")
    class ThreadIsolation {

        @Test
        @DisplayName("should not leak context across threads")
        void shouldNotLeakAcrossThreads() throws InterruptedException {
            var ctx = new CorrelationContext("main-corr", "tenant-1", null, null, null, null);
            CorrelationContextHolder.set(ctx);

            AtomicReference<Boolean> otherThreadHasContext = new AtomicReference<>();
            Thread other = new Thread(() -> {
                otherThreadHasContext.set(CorrelationContextHolder.get().isPresent());
            });
            other.start();
            other.join();

            assertThat(otherThreadHasContext.get()).isFalse();
        }
    }
}
