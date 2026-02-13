package com.orion.servicetemplate.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GrpcExceptionInterceptor}.
 *
 * <p>WHY: Verifies that Java exceptions are mapped to the correct gRPC status codes. Without this,
 * all exceptions become UNKNOWN — useless for client-side error handling.
 */
@DisplayName("GrpcExceptionInterceptor")
class GrpcExceptionInterceptorTest {

    private final GrpcExceptionInterceptor interceptor = new GrpcExceptionInterceptor();

    @Test
    @DisplayName("maps IllegalArgumentException to INVALID_ARGUMENT")
    void mapsIllegalArgumentToInvalidArgument() {
        Status status = interceptor.mapException(new IllegalArgumentException("bad input"));

        assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(status.getDescription()).isEqualTo("bad input");
    }

    @Test
    @DisplayName("maps IllegalStateException to FAILED_PRECONDITION")
    void mapsIllegalStateToFailedPrecondition() {
        Status status = interceptor.mapException(new IllegalStateException("wrong state"));

        assertThat(status.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(status.getDescription()).isEqualTo("wrong state");
    }

    @Test
    @DisplayName("maps unknown Exception to INTERNAL")
    void mapsUnknownExceptionToInternal() {
        Status status = interceptor.mapException(new RuntimeException("oops"));

        assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getDescription()).isEqualTo("Internal server error");
    }
}
