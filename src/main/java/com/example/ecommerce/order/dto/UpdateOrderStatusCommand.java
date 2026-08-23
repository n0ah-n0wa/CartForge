package com.example.ecommerce.order.dto;

import com.example.ecommerce.order.OrderStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Administrative status change. The lifecycle rules are enforced by the order
 * itself, not by this payload.
 */
public record UpdateOrderStatusCommand(
        @NotNull OrderStatus status
) {
}
