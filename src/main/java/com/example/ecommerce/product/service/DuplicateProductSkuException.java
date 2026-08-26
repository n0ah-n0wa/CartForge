package com.example.ecommerce.product.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class DuplicateProductSkuException extends DomainApiException {

    public DuplicateProductSkuException(String sku) {
        super("DUPLICATE_PRODUCT_SKU", HttpStatus.CONFLICT, "Product SKU already exists: " + sku);
    }
}
