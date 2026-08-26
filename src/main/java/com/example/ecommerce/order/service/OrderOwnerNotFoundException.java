package com.example.ecommerce.order.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class OrderOwnerNotFoundException extends DomainApiException {

    public OrderOwnerNotFoundException(long userId) {
        super("ORDER_OWNER_NOT_FOUND", HttpStatus.UNAUTHORIZED, "Order owner " + userId + " was not found");
    }
}
