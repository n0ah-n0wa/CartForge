package com.example.ecommerce.cart.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class InvalidCartQuantityException extends DomainApiException {

    public InvalidCartQuantityException(String message) {
        super("INVALID_CART_QUANTITY", HttpStatus.BAD_REQUEST, message);
    }
}
