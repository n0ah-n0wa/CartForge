package com.example.ecommerce.inventory.service;

/**
 * Raised when a concurrent inventory update loses the optimistic lock.
 * Maps to HTTP 409 ({@code INVENTORY_CONFLICT}).
 */
public class InventoryConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long productId;

    public InventoryConflictException(Long productId) {
        super("Inventory for product " + productId + " was modified concurrently");
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
