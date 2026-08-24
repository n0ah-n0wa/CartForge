package com.example.ecommerce.user.dto;

import com.example.ecommerce.user.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Safe user representation for registration and profile responses.
 * Never includes a password hash.
 */
public record UserResponse(
        Long id,
        String email,
        String firstName,
        String lastName,
        UserRole role,
        boolean enabled,
        @Schema(description = "UTC instant") Instant createdAt,
        @Schema(description = "UTC instant") Instant updatedAt
) {
}
