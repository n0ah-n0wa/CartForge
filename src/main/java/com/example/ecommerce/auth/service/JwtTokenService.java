package com.example.ecommerce.auth.service;

import com.example.ecommerce.auth.dto.AccessTokenResponse;
import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.security.JwtClaims;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the access token described in section 16: subject (user id), email,
 * role, issued-at, and expiration. Validation is handled by the configured
 * {@code JwtDecoder} on the resource-server filter chain.
 */
@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final ApplicationProperties properties;

    public JwtTokenService(JwtEncoder jwtEncoder, ApplicationProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public AccessTokenResponse issue(AuthenticatedUser user) {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusMillis(properties.jwt().expirationMs());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(user.userId()))
                .claim(JwtClaims.EMAIL, user.email())
                .claim(JwtClaims.ROLE, user.role().name())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        String token = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return new AccessTokenResponse(token, AccessTokenResponse.BEARER, expiresAt);
    }
}
