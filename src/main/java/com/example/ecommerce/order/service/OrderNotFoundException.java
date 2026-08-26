package com.example.ecommerce.order.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class OrderNotFoundException extends DomainApiException {

    public OrderNotFoundException(Long orderId) {
        super("ORDER_NOT_FOUND", HttpStatus.NOT_FOUND, "Order not found: " + orderId);
    }
}
