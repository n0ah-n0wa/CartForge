package com.example.ecommerce.common.exception;

import com.example.ecommerce.common.logging.CorrelationIds;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Builds the specification error envelope without copying exception messages that
 * might contain SQL, class names, or other internals.
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ApiErrorResponse of(HttpStatus status, String code, String message, String path) {
        return new ApiErrorResponse(
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                status.value(),
                code,
                Objects.requireNonNullElse(message, "Error"),
                path == null ? "" : path,
                CorrelationIds.currentOrEmpty());
    }

    public static ApiErrorResponse of(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return of(status, code, message, request == null ? "" : request.getRequestURI());
    }

    public static ResponseEntity<ApiErrorResponse> entity(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(of(status, code, message, request));
    }
}
