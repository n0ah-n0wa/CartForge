package com.example.ecommerce.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload. There is deliberately no role field: registration always
 * creates a {@code CUSTOMER}.
 *
 * <p>The upper bound on the password is not cosmetic. BCrypt only consumes the
 * first 72 bytes, so anything longer would be silently truncated.
 */
public record RegistrationRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 12, max = 72) String password,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName
) {
    /**
     * Redacted so the password cannot reach a log, an error message, or a stack
     * trace through the record's generated {@code toString}.
     */
    @Override
    public String toString() {
        return "RegistrationRequest[email=" + email
                + ", password=****, firstName=" + firstName
                + ", lastName=" + lastName + "]";
    }
}
