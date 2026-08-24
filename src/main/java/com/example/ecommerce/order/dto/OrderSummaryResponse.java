package com.example.ecommerce.order.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Order list projection. Listing orders must not load every line.
 */
public record OrderSummaryResponse(
        Long id,
        String orderNumber,
        OrderStatus status,
        @Schema(example = "99.00") BigDecimal totalAmount,
        @Schema(example = "EUR") CurrencyCode currency,
        @Schema(description = "UTC instant") Instant createdAt
) {
}
