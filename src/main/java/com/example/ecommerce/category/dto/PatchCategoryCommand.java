package com.example.ecommerce.category.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial category update. At least one field must be supplied.
 */
public record PatchCategoryCommand(
        @Size(max = 150) String name,
        @Size(max = 180) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,
        @Size(max = 4000) String description,
        Boolean active
) {
    @AssertTrue(message = "At least one field must be provided")
    public boolean hasAtLeastOneField() {
        return name != null || slug != null || description != null || active != null;
    }
}
