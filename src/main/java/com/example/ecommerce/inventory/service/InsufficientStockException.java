package com.example.ecommerce.inventory.service;

/**
 * Raised when a requested stock quantity cannot be satisfied.
 * Maps to HTTP 409 ({@code INSUFFICIENT_STOCK}).
 */
public class InsufficientStockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long productId;
    private final int available;
    private final int requested;

    public InsufficientStockException(Long productId, int available, int requested) {
        super("Insufficient stock for product %d: available=%d, requested=%d"
                .formatted(productId, available, requested));
        this.productId = productId;
        this.available = available;
        this.requested = requested;
    }

    public Long getProductId() {
        return productId;
    }

    public int getAvailable() {
        return available;
    }

    public int getRequested() {
        return requested;
    }
}
