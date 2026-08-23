package com.example.ecommerce.user.dto;

import com.example.ecommerce.user.UserRole;
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
        Instant createdAt,
        Instant updatedAt
) {
}
