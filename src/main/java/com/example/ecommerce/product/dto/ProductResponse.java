package com.example.ecommerce.product.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Safe catalog representation. The entity is never returned from the API.
 * {@code version} is included so clients can perform optimistic updates.
 */
public record ProductResponse(
        Long id,
        String sku,
        String name,
        String slug,
        String description,
        BigDecimal price,
        CurrencyCode currency,
        int stockQuantity,
        boolean active,
        boolean purchasable,
        Long version,
        ProductCategoryResponse category,
        Instant createdAt,
        Instant updatedAt
) {
}
