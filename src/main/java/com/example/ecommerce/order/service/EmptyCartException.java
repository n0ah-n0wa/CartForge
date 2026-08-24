package com.example.ecommerce.order.service;

/**
 * Raised when checkout is attempted with no cart lines.
 * Maps to HTTP 409 ({@code EMPTY_CART}).
 */
public class EmptyCartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmptyCartException() {
        super("Cannot checkout an empty cart");
    }
}
