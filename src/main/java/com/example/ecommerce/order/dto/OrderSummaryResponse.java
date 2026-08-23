package com.example.ecommerce.order.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Order list projection. Listing orders must not load every line.
 */
public record OrderSummaryResponse(
        Long id,
        String orderNumber,
        OrderStatus status,
        BigDecimal totalAmount,
        CurrencyCode currency,
        Instant createdAt
) {
}
