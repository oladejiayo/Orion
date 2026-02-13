package com.orion.servicetemplate.infrastructure.web;

import com.orion.observability.CorrelationContextHolder;
import java.net.URI;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler — maps exceptions to RFC 7807 ProblemDetail responses.
 *
 * <p>WHY: Replaces Express error-handling middleware from the TypeScript story. Spring's {@link
 * RestControllerAdvice} centralises error handling for all {@code @RestController} endpoints. Using
 * RFC 7807 {@link ProblemDetail} (built into Spring 6+) gives clients a standard, machine-readable
 * error format:
 *
 * <pre>
 * {
 *   "type": "https://orion.com/errors/bad-request",
 *   "title": "Bad Request",
 *   "status": 400,
 *   "detail": "Instrument ID must not be null",
 *   "timestamp": "2025-07-12T10:30:00Z",
 *   "correlationId": "abc-123"
 * }
 * </pre>
 *
 * <p>Every error response includes the correlation ID so support teams can trace errors back to
 * specific log entries.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        problem.setType(URI.create("https://orion.com/errors/bad-request"));
        enrichWithCorrelation(problem);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        String detail =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("Validation failed");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Validation Error");
        problem.setType(URI.create("https://orion.com/errors/validation"));
        enrichWithCorrelation(problem);
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Internal server error", ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Internal Server Error");
        problem.setType(URI.create("https://orion.com/errors/internal"));
        enrichWithCorrelation(problem);
        return problem;
    }

    /**
     * Enriches the ProblemDetail with correlation ID and timestamp. WHY: Clients need the
     * correlation ID to reference when contacting support.
     */
    private void enrichWithCorrelation(ProblemDetail problem) {
        problem.setProperty("timestamp", Instant.now().toString());
        CorrelationContextHolder.get()
                .ifPresent(ctx -> problem.setProperty("correlationId", ctx.correlationId()));
    }
}
