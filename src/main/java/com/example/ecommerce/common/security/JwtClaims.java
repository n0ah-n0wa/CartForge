package com.example.ecommerce.common.security;

/**
 * Names of the non-registered claims the specification requires. Shared by the
 * issuer and the authority converter so the two cannot drift apart.
 */
public final class JwtClaims {

    /** Registered {@code sub} claim carries the user id. */
    public static final String EMAIL = "email";
    public static final String ROLE = "role";

    private JwtClaims() {
    }
}
