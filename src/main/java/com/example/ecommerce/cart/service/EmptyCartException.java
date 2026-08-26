package com.example.ecommerce.cart.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when checkout is attempted with no cart lines.
 * Maps to HTTP 409 ({@code EMPTY_CART}).
 */
public class EmptyCartException extends DomainApiException {

    public EmptyCartException() {
        super("EMPTY_CART", HttpStatus.CONFLICT, "Cannot checkout an empty cart");
    }
}
