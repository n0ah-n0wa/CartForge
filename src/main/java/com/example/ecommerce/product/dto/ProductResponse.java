package com.example.ecommerce.product.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "Amount with scale 2", example = "49.50") BigDecimal price,
        @Schema(description = "ISO-compatible currency", example = "EUR") CurrencyCode currency,
        int stockQuantity,
        boolean active,
        boolean purchasable,
        Long version,
        ProductCategoryResponse category,
        @Schema(description = "UTC instant", example = "2026-08-22T18:30:00.000Z") Instant createdAt,
        @Schema(description = "UTC instant", example = "2026-08-22T18:30:00.000Z") Instant updatedAt
) {
}
