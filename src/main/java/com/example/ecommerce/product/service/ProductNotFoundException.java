package com.example.ecommerce.product.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class ProductNotFoundException extends DomainApiException {

    public ProductNotFoundException(Long productId) {
        super("PRODUCT_NOT_FOUND", HttpStatus.NOT_FOUND, "Product not found: " + productId);
    }
}
