package com.example.ecommerce.cart.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record CartItemResponse(
        Long id,
        Long productId,
        String sku,
        String name,
        String slug,
        @Schema(example = "49.50") BigDecimal unitPrice,
        @Schema(example = "EUR") CurrencyCode currency,
        int quantity,
        @Schema(example = "99.00") BigDecimal lineTotal
) {
}
