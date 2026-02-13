package com.orion.servicetemplate.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orion.observability.CorrelationContextHolder;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GrpcCorrelationInterceptor}.
 *
 * <p>WHY: Verifies that correlation IDs flow correctly through gRPC calls, enabling end-to-end
 * request tracing across service-to-service communication.
 */
@DisplayName("GrpcCorrelationInterceptor")
class GrpcCorrelationInterceptorTest {

    private final GrpcCorrelationInterceptor interceptor = new GrpcCorrelationInterceptor();

    @AfterEach
    void cleanup() {
        CorrelationContextHolder.clear();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("extracts correlation ID from gRPC metadata")
    void extractsCorrelationIdFromMetadata() {
        var metadata = new Metadata();
        metadata.put(GrpcCorrelationInterceptor.CORRELATION_ID_KEY, "grpc-test-123");

        ServerCall<String, String> call = mock(ServerCall.class);
        ServerCallHandler<String, String> handler = mock(ServerCallHandler.class);
        when(handler.startCall(any(), any())).thenReturn(new ServerCall.Listener<>() {});

        interceptor.interceptCall(call, metadata, handler);

        var ctx = CorrelationContextHolder.get();
        assertThat(ctx).isPresent();
        assertThat(ctx.get().correlationId()).isEqualTo("grpc-test-123");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("generates correlation ID when absent from metadata")
    void generatesCorrelationIdWhenAbsent() {
        var metadata = new Metadata(); // no correlation ID

        ServerCall<String, String> call = mock(ServerCall.class);
        ServerCallHandler<String, String> handler = mock(ServerCallHandler.class);
        when(handler.startCall(any(), any())).thenReturn(new ServerCall.Listener<>() {});

        interceptor.interceptCall(call, metadata, handler);

        var ctx = CorrelationContextHolder.get();
        assertThat(ctx).isPresent();
        assertThat(ctx.get().correlationId()).isNotBlank();
    }
}
