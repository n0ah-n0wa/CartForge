package com.example.ecommerce.category.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record CategoryResponse(
        Long id,
        String name,
        String slug,
        String description,
        boolean active,
        @Schema(description = "UTC instant") Instant createdAt,
        @Schema(description = "UTC instant") Instant updatedAt
) {
}
