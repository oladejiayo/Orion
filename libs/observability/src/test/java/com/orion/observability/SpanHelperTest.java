package com.orion.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SpanHelper} — validates span creation, correlation context
 * attachment, error recording, and the withSpan helper.
 * <p>
 * Uses {@link InMemorySpanExporter} directly (rather than OpenTelemetryExtension)
 * for reliable span collection in nested JUnit 5 test classes.
 */
@DisplayName("SpanHelper")
class SpanHelperTest {

    private InMemorySpanExporter spanExporter;
    private SpanHelper spanHelper;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        OpenTelemetrySdk otelSdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
        Tracer tracer = otelSdk.getTracer("test-tracer");
        spanHelper = new SpanHelper(tracer);
    }

    @AfterEach
    void cleanup() {
        CorrelationContextHolder.clear();
        spanExporter.reset();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should reject null tracer")
        void shouldRejectNullTracer() {
            assertThatThrownBy(() -> new SpanHelper(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tracer");
        }

        @Test
        @DisplayName("should expose underlying tracer")
        void shouldExposeTracer() {
            assertThat(spanHelper.tracer()).isNotNull();
        }
    }

    @Nested
    @DisplayName("withSpan (Callable)")
    class WithSpanCallable {

        @Test
        @DisplayName("should create a span and return the result")
        void shouldCreateSpanAndReturnResult() throws Exception {
            String result = spanHelper.withSpan("test-operation", () -> "hello");

            assertThat(result).isEqualTo("hello");

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).getName()).isEqualTo("test-operation");
            assertThat(spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        }

        @Test
        @DisplayName("should record error on exception and re-throw")
        void shouldRecordErrorOnException() {
            assertThatThrownBy(() ->
                    spanHelper.withSpan("failing-operation", () -> {
                        throw new RuntimeException("test error");
                    })
            ).isInstanceOf(RuntimeException.class).hasMessage("test error");

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(spans.get(0).getStatus().getDescription()).contains("test error");
            assertThat(spans.get(0).getEvents()).isNotEmpty(); // Exception recorded
        }

        @Test
        @DisplayName("should attach correlation context attributes to span")
        void shouldAttachCorrelationContext() throws Exception {
            var ctx = new CorrelationContext("corr-abc", "tenant-xyz", "user-42", null, null, null);
            CorrelationContextHolder.set(ctx);

            spanHelper.withSpan("correlated-op", () -> "ok");

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);

            var attributes = spans.get(0).getAttributes();
            assertThat(attributes.get(AttributeKey.stringKey("correlation.id")))
                    .isEqualTo("corr-abc");
            assertThat(attributes.get(AttributeKey.stringKey("tenant.id")))
                    .isEqualTo("tenant-xyz");
            assertThat(attributes.get(AttributeKey.stringKey("user.id")))
                    .isEqualTo("user-42");
        }

        @Test
        @DisplayName("should work without correlation context")
        void shouldWorkWithoutCorrelationContext() throws Exception {
            String result = spanHelper.withSpan("no-context-op", () -> "fine");
            assertThat(result).isEqualTo("fine");

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        }
    }

    @Nested
    @DisplayName("withSpan (with kind and attributes)")
    class WithSpanAdvanced {

        @Test
        @DisplayName("should set span kind")
        void shouldSetSpanKind() throws Exception {
            spanHelper.withSpan("server-op", SpanKind.SERVER, Map.of(), () -> "ok");

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).getKind()).isEqualTo(SpanKind.SERVER);
        }

        @Test
        @DisplayName("should set custom attributes")
        void shouldSetCustomAttributes() throws Exception {
            spanHelper.withSpan("attributed-op", SpanKind.INTERNAL,
                    Map.of("custom.key", "custom-value"), () -> "ok");

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).getAttributes()
                    .get(AttributeKey.stringKey("custom.key")))
                    .isEqualTo("custom-value");
        }
    }

    @Nested
    @DisplayName("withSpan (Runnable)")
    class WithSpanRunnable {

        @Test
        @DisplayName("should create span for void operation")
        void shouldCreateSpanForVoidOp() {
            spanHelper.withSpan("void-op", () -> {
                // no-op
            });

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);
            assertThat(spans.get(0).getName()).isEqualTo("void-op");
            assertThat(spans.get(0).getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        }

        @Test
        @DisplayName("should propagate runtime exceptions from runnable")
        void shouldPropagateRuntimeExceptions() {
            assertThatThrownBy(() ->
                    spanHelper.withSpan("failing-void-op", (Runnable) () -> {
                        throw new IllegalStateException("runnable failure");
                    })
            ).isInstanceOf(IllegalStateException.class).hasMessage("runnable failure");
        }
    }
}
