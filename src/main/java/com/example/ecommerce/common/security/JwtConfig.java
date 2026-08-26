package com.example.ecommerce.common.security;

import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.user.UserRole;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * HMAC signing for self-contained access tokens. The secret comes from
 * configuration only; there is no default and nothing is hardcoded.
 */
@Configuration
public class JwtConfig {

    static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;

    /** HS256 requires a key of at least 256 bits. */
    static final int MINIMUM_SECRET_BYTES = 32;

    @Bean
    JwtEncoder jwtEncoder(ApplicationProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(ApplicationProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(ALGORITHM)
                .build();
        decoder.setJwtValidator(tokenValidator());
        return decoder;
    }

    /**
     * Nimbus verifies the signature, and the default validator checks {@code exp}
     * only when it is present. Section 16 makes those claims mandatory, so they are
     * required here: otherwise a signed token with no {@code exp} would never
     * expire, and one with no {@code sub} would be granted its role without an
     * identifiable principal.
     *
     * <p>{@code iat} is not listed because Spring's default claim-set converter
     * derives it from {@code exp} when it is absent, so a decoded token always has
     * one. The issuer sets it explicitly.
     */
    static OAuth2TokenValidator<Jwt> tokenValidator() {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, JwtConfig::hasText),
                new JwtClaimValidator<String>(JwtClaims.EMAIL, JwtConfig::hasText),
                new JwtClaimValidator<String>(JwtClaims.ROLE, JwtConfig::isKnownRole));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Only {@link UserRole} names are accepted. A signed token that carried
     * {@code ROLE_ADMIN}, {@code admin}, or an invented role must not authenticate.
     */
    static boolean isKnownRole(String value) {
        if (!hasText(value)) {
            return false;
        }
        try {
            UserRole.valueOf(value);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    static SecretKey signingKey(ApplicationProperties properties) {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least " + MINIMUM_SECRET_BYTES + " bytes for " + ALGORITHM.getName());
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
