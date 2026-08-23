package com.example.ecommerce.product.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Partial product update. {@code version} is mandatory so optimistic locking
 * cannot be bypassed. At least one mutable field must be supplied.
 */
public record PatchProductCommand(
        @NotNull Long version,
        @Size(max = 200) String name,
        @Size(max = 220) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
        @Size(max = 4000) String description,
        @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal price,
        CurrencyCode currency,
        @PositiveOrZero Integer stockQuantity,
        Long categoryId,
        Boolean active
) {
    @AssertTrue(message = "At least one field must be provided")
    public boolean hasAtLeastOneMutableField() {
        return name != null
                || slug != null
                || description != null
                || price != null
                || currency != null
                || stockQuantity != null
                || categoryId != null
                || active != null;
    }
}
