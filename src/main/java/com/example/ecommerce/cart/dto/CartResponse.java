package com.example.ecommerce.cart.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CartResponse(
        Long id,
        List<CartItemResponse> items,
        CurrencyCode currency,
        BigDecimal total,
        int totalQuantity,
        Instant createdAt,
        Instant updatedAt
) {
    public CartResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public List<CartItemResponse> items() {
        return List.copyOf(items);
    }
}
