package com.example.ecommerce.product.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when a client-supplied version does not match the persisted product.
 * Maps to HTTP 409 so callers can reload and retry.
 */
public class ProductVersionConflictException extends DomainApiException {

    private static final long serialVersionUID = 1L;

    private final Long productId;
    private final Long expectedVersion;
    private final Long actualVersion;

    public ProductVersionConflictException(Long productId, Long expectedVersion, Long actualVersion) {
        super(
                "PRODUCT_VERSION_CONFLICT",
                HttpStatus.CONFLICT,
                "Product " + productId + " was modified concurrently (expected version "
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
