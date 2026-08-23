package com.example.ecommerce.cart.service;

public class CartItemNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long productId;

    public CartItemNotFoundException(Long productId) {
        super("Cart does not contain product: " + productId);
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
