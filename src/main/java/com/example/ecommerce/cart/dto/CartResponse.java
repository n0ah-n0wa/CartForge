package com.example.ecommerce.cart.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CartResponse(
        Long id,
        List<CartItemResponse> items,
        @Schema(example = "EUR") CurrencyCode currency,
        @Schema(description = "Sum of line totals", example = "99.00") BigDecimal total,
        int totalQuantity,
        @Schema(description = "UTC instant") Instant createdAt,
        @Schema(description = "UTC instant") Instant updatedAt
) {
    public CartResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public List<CartItemResponse> items() {
        return List.copyOf(items);
    }
}
