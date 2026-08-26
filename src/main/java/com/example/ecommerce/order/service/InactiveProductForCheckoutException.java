package com.example.ecommerce.order.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class InactiveProductForCheckoutException extends DomainApiException {

    private final Long productId;

    public InactiveProductForCheckoutException(Long productId) {
        super("INACTIVE_PRODUCT", HttpStatus.BAD_REQUEST, "Product " + productId + " is not active");
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
