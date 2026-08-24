package com.example.ecommerce.order.service;

/**
 * Raised when {@code Idempotency-Key} is blank, too long, or not printable ASCII.
 * Maps to HTTP 400 ({@code IDEMPOTENCY_KEY_INVALID}).
 */
public class InvalidIdempotencyKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be 1 to 255 printable ASCII characters");
    }
}
