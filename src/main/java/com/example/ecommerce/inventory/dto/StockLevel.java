package com.example.ecommerce.inventory.dto;

/**
 * Snapshot of a product's stock after an inventory operation.
 */
public record StockLevel(
        Long productId,
        int stockQuantity,
        Long version
) {
}
