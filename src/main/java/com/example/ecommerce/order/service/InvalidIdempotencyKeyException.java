package com.example.ecommerce.order.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class InvalidIdempotencyKeyException extends DomainApiException {

    public InvalidIdempotencyKeyException() {
        super(
                "IDEMPOTENCY_KEY_INVALID",
                HttpStatus.BAD_REQUEST,
                "Idempotency-Key is missing, blank, too long, or contains invalid characters");
    }

    public InvalidIdempotencyKeyException(String message) {
        super("IDEMPOTENCY_KEY_INVALID", HttpStatus.BAD_REQUEST, message);
    }
}
