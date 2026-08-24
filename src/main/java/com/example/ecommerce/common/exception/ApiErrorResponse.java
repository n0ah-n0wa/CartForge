package com.example.ecommerce.common.exception;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Standard error envelope required by the specification. Internal details such
 * as stack traces or SQL must never appear here.
 */
@Schema(name = "ApiErrorResponse", description = "Safe, consistent API error body")
public record ApiErrorResponse(
        @Schema(description = "UTC instant", example = "2026-08-22T18:30:00.000Z") Instant timestamp,
        @Schema(example = "409") int status,
        @Schema(example = "INSUFFICIENT_STOCK") String code,
        @Schema(example = "Insufficient stock for product 42") String message,
        @Schema(example = "/api/v1/orders") String path
) {
}
