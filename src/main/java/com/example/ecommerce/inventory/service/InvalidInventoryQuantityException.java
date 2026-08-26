package com.example.ecommerce.inventory.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class InvalidInventoryQuantityException extends DomainApiException {

    public InvalidInventoryQuantityException(String message) {
        super("INVALID_INVENTORY_QUANTITY", HttpStatus.BAD_REQUEST, message);
    }
}
