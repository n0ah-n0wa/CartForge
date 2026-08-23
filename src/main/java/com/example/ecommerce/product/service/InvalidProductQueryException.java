package com.example.ecommerce.product.service;

/**
 * Raised when catalog filter parameters are invalid. Maps to HTTP 400.
 */
public class InvalidProductQueryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidProductQueryException(String message) {
        super(message);
    }
}
