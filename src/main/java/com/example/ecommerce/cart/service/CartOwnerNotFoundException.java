package com.example.ecommerce.cart.service;

public class CartOwnerNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long userId;

    public CartOwnerNotFoundException(Long userId) {
        super("Authenticated cart owner was not found: " + userId);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
