package com.example.ecommerce.inventory.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class InventoryConflictException extends DomainApiException {

    private final Long productId;

    public InventoryConflictException(Long productId) {
        super("INVENTORY_CONFLICT", HttpStatus.CONFLICT, "Inventory conflict for product " + productId);
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
