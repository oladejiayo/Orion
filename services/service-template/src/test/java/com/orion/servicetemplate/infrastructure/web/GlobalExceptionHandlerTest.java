package com.orion.servicetemplate.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>WHY: The exception handler is the last line of defense for unhandled errors. Testing it as a
 * plain unit (no Spring context) ensures the mapping logic is correct and fast to validate.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("maps IllegalArgumentException to 400 Bad Request")
    void handlesIllegalArgumentAsBadRequest() {
        ProblemDetail result =
                handler.handleIllegalArgument(new IllegalArgumentException("invalid input"));

        assertThat(result.getStatus()).isEqualTo(400);
        assertThat(result.getDetail()).isEqualTo("invalid input");
        assertThat(result.getTitle()).isEqualTo("Bad Request");
    }

    @Test
    @DisplayName("maps generic Exception to 500 Internal Server Error")
    void handlesGenericExceptionAsInternalError() {
        ProblemDetail result = handler.handleGeneric(new RuntimeException("something broke"));

        assertThat(result.getStatus()).isEqualTo(500);
        assertThat(result.getTitle()).isEqualTo("Internal Server Error");
    }

    @Test
    @DisplayName("error response includes timestamp")
    void errorResponseIncludesTimestamp() {
        ProblemDetail result = handler.handleGeneric(new RuntimeException("oops"));

        assertThat(result.getProperties()).containsKey("timestamp");
    }
}
