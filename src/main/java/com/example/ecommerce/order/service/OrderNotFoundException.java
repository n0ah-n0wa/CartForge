package com.example.ecommerce.order.service;

/**
 * Raised when an order cannot be found. Customer reads use the same response
 * whether the id does not exist or belongs to another user.
 */
public class OrderNotFoundException extends RuntimeException {

    private final Long orderId;

    public OrderNotFoundException(Long orderId) {
        super("Order not found: " + orderId);
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
