package com.example.ecommerce.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * The historical snapshot as it was captured, not the product's current state.
 */
public record OrderItemResponse(
        Long id,
        Long productId,
        String productName,
        String sku,
        @Schema(example = "49.50") BigDecimal unitPrice,
        int quantity,
        @Schema(example = "99.00") BigDecimal lineTotal
) {
}
