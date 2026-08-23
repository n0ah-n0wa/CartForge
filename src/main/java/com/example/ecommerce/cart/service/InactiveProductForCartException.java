package com.example.ecommerce.cart.service;

public class InactiveProductForCartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long productId;

    public InactiveProductForCartException(Long productId) {
        super("Inactive product cannot be added to the cart: " + productId);
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
