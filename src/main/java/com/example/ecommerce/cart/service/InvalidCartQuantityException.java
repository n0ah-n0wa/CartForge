package com.example.ecommerce.cart.service;

public class InvalidCartQuantityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidCartQuantityException(String message) {
        super(message);
    }
}
