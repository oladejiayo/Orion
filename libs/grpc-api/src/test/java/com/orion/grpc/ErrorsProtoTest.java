package com.orion.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.orion.common.v1.ErrorDetail;
import com.orion.common.v1.ErrorResponse;
import com.orion.common.v1.Timestamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the generated error messages from errors.proto: ErrorDetail with optional fields,
 * ErrorResponse with nested details, metadata maps.
 */
@DisplayName("Errors Proto")
class ErrorsProtoTest {

    @Nested
    @DisplayName("ErrorDetail")
    class ErrorDetailTests {

        @Test
        @DisplayName("should build with code and message")
        void shouldBuildBasic() {
            var detail =
                    ErrorDetail.newBuilder()
                            .setCode("VALIDATION_ERROR")
                            .setMessage("Field is required")
                            .build();

            assertThat(detail.getCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(detail.getMessage()).isEqualTo("Field is required");
            assertThat(detail.hasField()).isFalse();
        }

        @Test
        @DisplayName("should support optional field name")
        void shouldSupportOptionalField() {
            var detail =
                    ErrorDetail.newBuilder()
                            .setCode("FIELD_REQUIRED")
                            .setMessage("instrument_id is required")
                            .setField("instrument_id")
                            .build();

            assertThat(detail.hasField()).isTrue();
            assertThat(detail.getField()).isEqualTo("instrument_id");
        }

        @Test
        @DisplayName("should support metadata map")
        void shouldSupportMetadata() {
            var detail =
                    ErrorDetail.newBuilder()
                            .setCode("LIMIT_EXCEEDED")
                            .setMessage("Notional exceeds limit")
                            .putMetadata("max_notional", "50000000")
                            .putMetadata("requested_notional", "75000000")
                            .build();

            assertThat(detail.getMetadataMap())
                    .containsEntry("max_notional", "50000000")
                    .containsEntry("requested_notional", "75000000")
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("ErrorResponse")
    class ErrorResponseTests {

        @Test
        @DisplayName("should build complete error response")
        void shouldBuildComplete() {
            var response =
                    ErrorResponse.newBuilder()
                            .setErrorCode("NOT_FOUND")
                            .setMessage("RFQ not found")
                            .setCorrelationId("corr-abc-123")
                            .setTimestamp(Timestamp.newBuilder().setSeconds(1_700_000_000L))
                            .addDetails(
                                    ErrorDetail.newBuilder()
                                            .setCode("ENTITY_NOT_FOUND")
                                            .setMessage("RFQ with ID rfq-42 does not exist")
                                            .setField("rfq_id"))
                            .build();

            assertThat(response.getErrorCode()).isEqualTo("NOT_FOUND");
            assertThat(response.getMessage()).isEqualTo("RFQ not found");
            assertThat(response.getCorrelationId()).isEqualTo("corr-abc-123");
            assertThat(response.getDetailsCount()).isEqualTo(1);
            assertThat(response.getDetails(0).getCode()).isEqualTo("ENTITY_NOT_FOUND");
        }

        @Test
        @DisplayName("should support multiple error details")
        void shouldSupportMultipleDetails() {
            var response =
                    ErrorResponse.newBuilder()
                            .setErrorCode("VALIDATION_FAILED")
                            .setMessage("Request validation failed")
                            .setCorrelationId("corr-123")
                            .addDetails(
                                    ErrorDetail.newBuilder()
                                            .setCode("FIELD_REQUIRED")
                                            .setMessage("instrument_id required"))
                            .addDetails(
                                    ErrorDetail.newBuilder()
                                            .setCode("FIELD_REQUIRED")
                                            .setMessage("quantity required"))
                            .addDetails(
                                    ErrorDetail.newBuilder()
                                            .setCode("INVALID_VALUE")
                                            .setMessage("side must be BUY or SELL"))
                            .build();

            assertThat(response.getDetailsCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("should serialize and deserialize correctly")
        void shouldRoundTrip() throws Exception {
            var response =
                    ErrorResponse.newBuilder()
                            .setErrorCode("INTERNAL")
                            .setMessage("Unexpected error")
                            .setCorrelationId("corr-456")
                            .setTimestamp(
                                    Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(500))
                            .build();

            var parsed = ErrorResponse.parseFrom(response.toByteArray());
            assertThat(parsed).isEqualTo(response);
        }
    }
}
