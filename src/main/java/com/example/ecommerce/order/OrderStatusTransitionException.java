package com.example.ecommerce.order;

/**
 * Raised when an order is asked to move to a status the lifecycle forbids.
 * Maps to a controlled business error (HTTP 409) once controllers exist.
 */
public class OrderStatusTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final OrderStatus from;
    private final OrderStatus to;

    public OrderStatusTransitionException(OrderStatus from, OrderStatus to) {
        super("Order cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public OrderStatus getFrom() {
        return from;
    }

    public OrderStatus getTo() {
        return to;
    }
}
