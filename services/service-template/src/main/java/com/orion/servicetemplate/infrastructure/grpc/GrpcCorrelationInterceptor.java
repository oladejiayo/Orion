package com.orion.servicetemplate.infrastructure.grpc;

import com.orion.observability.CorrelationContext;
import com.orion.observability.CorrelationContextHolder;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.UUID;

/**
 * gRPC server interceptor that propagates correlation IDs from metadata.
 *
 * <p>WHY: Replaces the gRPC logging/correlation interceptor from the TypeScript story. When a gRPC
 * call arrives, this interceptor:
 *
 * <ol>
 *   <li>Extracts {@code x-correlation-id} from gRPC metadata (headers)
 *   <li>Falls back to generating a new UUID if absent
 *   <li>Sets the correlation context on {@link CorrelationContextHolder}
 *   <li>Cleans up the context when the call completes or is cancelled
 * </ol>
 *
 * <p>This interceptor is NOT a Spring {@code @Component} — it's registered with the gRPC server
 * when {@code grpc-server-spring-boot-starter} is added. For now, it's a standalone Java class
 * tested in isolation.
 */
public class GrpcCorrelationInterceptor implements ServerInterceptor {

    public static final Metadata.Key<String> CORRELATION_ID_KEY =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String correlationId = headers.get(CORRELATION_ID_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        var context = new CorrelationContext(correlationId, null, null, null, null, null);
        CorrelationContextHolder.set(context);

        try {
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                    next.startCall(call, headers)) {
                @Override
                public void onComplete() {
                    try {
                        super.onComplete();
                    } finally {
                        CorrelationContextHolder.clear();
                    }
                }

                @Override
                public void onCancel() {
                    try {
                        super.onCancel();
                    } finally {
                        CorrelationContextHolder.clear();
                    }
                }
            };
        } catch (Exception e) {
            CorrelationContextHolder.clear();
            throw e;
        }
    }
}
