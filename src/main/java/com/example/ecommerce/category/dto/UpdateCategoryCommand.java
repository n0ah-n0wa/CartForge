package com.example.ecommerce.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCategoryCommand(
        @NotBlank @Size(max = 150) String name,
        @NotBlank
        @Size(max = 180)
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$")
        String slug,
        @Size(max = 4000) String description,
        @NotNull Boolean active
) {
}
