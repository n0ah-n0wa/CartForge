package com.example.ecommerce.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Login result. The token itself is a credential, so it is redacted from
 * {@code toString} for the same reason passwords are.
 */
public record AccessTokenResponse(
        @Schema(description = "JWT access token") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "UTC expiry instant") Instant expiresAt
) {
    public static final String BEARER = "Bearer";

    @Override
    public String toString() {
        return "AccessTokenResponse[accessToken=****, tokenType=" + tokenType
                + ", expiresAt=" + expiresAt + "]";
    }
}
