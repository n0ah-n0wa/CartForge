package com.example.ecommerce.order.service;

/**
 * Raised when checkout references a product that is no longer active.
 * Maps to HTTP 400 ({@code INACTIVE_PRODUCT}).
 */
public class InactiveProductForCheckoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long productId;

    public InactiveProductForCheckoutException(Long productId) {
        super("Inactive product cannot be purchased: " + productId);
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
