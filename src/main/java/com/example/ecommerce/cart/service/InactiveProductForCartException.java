package com.example.ecommerce.cart.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class InactiveProductForCartException extends DomainApiException {

    public InactiveProductForCartException(Long productId) {
        super("INACTIVE_PRODUCT", HttpStatus.BAD_REQUEST, "Product " + productId + " is not active");
    }
}
