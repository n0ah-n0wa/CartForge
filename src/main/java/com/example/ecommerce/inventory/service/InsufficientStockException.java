package com.example.ecommerce.inventory.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class InsufficientStockException extends DomainApiException {

    private final Long productId;
    private final int available;
    private final int requested;

    public InsufficientStockException(Long productId, int available, int requested) {
        super(
                "INSUFFICIENT_STOCK",
                HttpStatus.CONFLICT,
                "Insufficient stock for product %d: available=%d, requested=%d"
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
