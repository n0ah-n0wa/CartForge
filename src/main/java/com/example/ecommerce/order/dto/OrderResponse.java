package com.example.ecommerce.order.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String orderNumber,
        OrderStatus status,
        BigDecimal totalAmount,
        CurrencyCode currency,
        String shippingAddress,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
    public OrderResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public List<OrderItemResponse> items() {
        return List.copyOf(items);
    }
}
