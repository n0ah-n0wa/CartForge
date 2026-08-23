package com.example.ecommerce.user.dto;

import com.example.ecommerce.user.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Internal write model for a new user after the password has already been hashed.
 * Authentication must never pass a plaintext password here.
 */
public record CreateUserCommand(
        @NotBlank @Email String email,
        @NotBlank String passwordHash,
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotNull UserRole role
) {
    public CreateUserCommand {
        role = role == null ? UserRole.CUSTOMER : role;
    }
}
