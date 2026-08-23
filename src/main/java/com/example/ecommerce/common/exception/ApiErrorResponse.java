package com.example.ecommerce.common.exception;

import java.time.Instant;

/**
 * Standard error envelope required by the specification. Internal details such
 * as stack traces or SQL must never appear here.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path
) {
}
