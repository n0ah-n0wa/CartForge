package com.example.ecommerce.product.service;

/**
 * Raised when a client-supplied version does not match the persisted product.
 * Maps to HTTP 409 so callers can reload and retry.
 */
public class ProductVersionConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long productId;
    private final Long expectedVersion;
    private final Long actualVersion;

    public ProductVersionConflictException(Long productId, Long expectedVersion, Long actualVersion) {
        super("Product " + productId + " was modified concurrently (expected version "
                + expectedVersion + ", actual " + actualVersion + ")");
        this.productId = productId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getExpectedVersion() {
        return expectedVersion;
    }

    public Long getActualVersion() {
        return actualVersion;
    }
}
