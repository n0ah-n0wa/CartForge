package com.example.ecommerce.auth.dto;

import java.time.Instant;

/**
 * Login result. The token itself is a credential, so it is redacted from
 * {@code toString} for the same reason passwords are.
 */
public record AccessTokenResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt
) {
    public static final String BEARER = "Bearer";

    @Override
    public String toString() {
        return "AccessTokenResponse[accessToken=****, tokenType=" + tokenType
                + ", expiresAt=" + expiresAt + "]";
    }
}
