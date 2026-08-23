package com.example.ecommerce.product.dto;

import com.example.ecommerce.common.persistence.CurrencyCode;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateProductCommand(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]*$") String sku,
        @NotBlank @Size(max = 200) String name,
        @NotBlank
        @Size(max = 220)
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$")
        String slug,
        @Size(max = 4000) String description,
        @NotNull @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal price,
        CurrencyCode currency,
        @PositiveOrZero int stockQuantity,
        @NotNull Long categoryId
) {
}
