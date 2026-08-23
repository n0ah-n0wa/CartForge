package com.example.ecommerce.cart.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import java.math.BigDecimal;

public record CartItemResponse(
        Long id,
        Long productId,
        String sku,
        String name,
        String slug,
        BigDecimal unitPrice,
        CurrencyCode currency,
        int quantity,
        BigDecimal lineTotal
) {
}
