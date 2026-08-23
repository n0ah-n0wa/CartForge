package com.example.ecommerce.auth.dto;

import com.example.ecommerce.user.UserRole;

/**
 * Outcome of a successful credential check. Carries exactly the claims the
 * specified JWT payload needs (subject, email, role); token issuance is a later
 * step and is not implemented yet.
 */
public record AuthenticatedUser(
        Long userId,
        String email,
        UserRole role
) {
}
