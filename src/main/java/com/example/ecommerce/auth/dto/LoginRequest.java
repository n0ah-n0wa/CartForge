package com.example.ecommerce.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload. The email is not {@code @Email}-validated: a malformed address
 * must fail as bad credentials, not as a validation error that distinguishes it
 * from a wrong password.
 */
public record LoginRequest(
        @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(max = 72) String password
) {
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
