package com.example.ecommerce.order.dto;

import java.math.BigDecimal;

/**
 * The historical snapshot as it was captured, not the product's current state.
 */
public record OrderItemResponse(
        Long id,
        Long productId,
        String productName,
        String sku,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal
) {
}
