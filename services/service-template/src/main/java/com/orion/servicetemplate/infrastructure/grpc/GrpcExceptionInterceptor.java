package com.orion.servicetemplate.infrastructure.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC server interceptor that maps Java exceptions to appropriate gRPC status codes.
 *
 * <p>WHY: Replaces the gRPC error-handling interceptor from the TypeScript story. Without this
 * interceptor, unhandled exceptions result in {@code Status.UNKNOWN} with no useful information for
 * the client. This interceptor:
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} → {@code INVALID_ARGUMENT} (client bug)
 *   <li>{@link IllegalStateException} → {@code FAILED_PRECONDITION} (state issue)
 *   <li>{@link StatusRuntimeException} → preserves the original status
 *   <li>All other exceptions → {@code INTERNAL} (server bug)
 * </ul>
 *
 * <p>Like {@link GrpcCorrelationInterceptor}, this is a standalone Java class registered with the
 * gRPC server via configuration — not a Spring bean.
 */
public class GrpcExceptionInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcExceptionInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        ServerCall<ReqT, RespT> wrappedCall =
                new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        // WHY: gRPC wraps unhandled exceptions as UNKNOWN.
                        // We unwrap and map to more specific status codes.
                        if (status.getCode() == Status.Code.UNKNOWN && status.getCause() != null) {
                            status = mapException(status.getCause());
                        }
                        super.close(status, trailers);
                    }
                };

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
                next.startCall(wrappedCall, headers)) {};
    }

    /** Maps a Java exception to an appropriate gRPC Status. Package-private for testing. */
    Status mapException(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException) {
            log.warn("gRPC bad request: {}", throwable.getMessage());
            return Status.INVALID_ARGUMENT
                    .withDescription(throwable.getMessage())
                    .withCause(throwable);
        }
        if (throwable instanceof IllegalStateException) {
            log.warn("gRPC failed precondition: {}", throwable.getMessage());
            return Status.FAILED_PRECONDITION
                    .withDescription(throwable.getMessage())
                    .withCause(throwable);
        }
        if (throwable instanceof StatusRuntimeException sre) {
            return sre.getStatus();
        }
        log.error("gRPC internal error", throwable);
        return Status.INTERNAL.withDescription("Internal server error").withCause(throwable);
    }
}
