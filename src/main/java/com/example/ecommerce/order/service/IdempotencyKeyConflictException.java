package com.example.ecommerce.order.service;

/**
 * Raised when the same user reuses an {@code Idempotency-Key} with a different
 * checkout body. Maps to HTTP 409 ({@code IDEMPOTENCY_KEY_REUSED}).
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyKeyConflictException() {
        super("Idempotency-Key was already used with a different request");
    }
}
