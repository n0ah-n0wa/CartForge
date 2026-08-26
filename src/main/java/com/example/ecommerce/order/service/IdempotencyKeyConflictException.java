package com.example.ecommerce.order.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class IdempotencyKeyConflictException extends DomainApiException {

    public IdempotencyKeyConflictException() {
        super(
                "IDEMPOTENCY_KEY_REUSED",
                HttpStatus.CONFLICT,
                "Idempotency-Key was already used with a different request");
    }
}
