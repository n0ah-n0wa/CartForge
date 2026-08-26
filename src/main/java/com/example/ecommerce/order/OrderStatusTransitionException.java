package com.example.ecommerce.order;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class OrderStatusTransitionException extends DomainApiException {

    private final OrderStatus from;
    private final OrderStatus to;

    public OrderStatusTransitionException(OrderStatus current, OrderStatus target) {
        super(
                "ORDER_STATUS_TRANSITION",
                HttpStatus.CONFLICT,
                "Cannot transition order from %s to %s".formatted(current, target));
        this.from = current;
        this.to = target;
    }

    public OrderStatus getFrom() {
        return from;
    }

    public OrderStatus getTo() {
        return to;
    }
}
