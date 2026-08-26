package com.example.ecommerce.product.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when catalog filter parameters are invalid. Maps to HTTP 400.
 */
public class InvalidProductQueryException extends DomainApiException {

    private static final long serialVersionUID = 1L;

    public InvalidProductQueryException(String message) {
        super("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, message);
    }
}
