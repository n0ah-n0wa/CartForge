package com.example.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.auth.dto.AccessTokenResponse;
import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.security.JwtClaims;
import com.example.ecommerce.user.UserRole;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtTokenServiceTest {

    private static final String SECRET = "test-only-jwt-secret-not-for-production";
    private static final String OTHER_SECRET = "another-test-only-secret-not-for-prod";
    private static final long EXPIRATION_MS = 900_000L;

    private final JwtEncoder encoder = encoderFor(SECRET);
    private final JwtDecoder decoder = decoderFor(SECRET);
    private final JwtTokenService tokenService = new JwtTokenService(encoder, properties(EXPIRATION_MS));

    @Test
    void issuesATokenCarryingEveryRequiredClaim() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        AccessTokenResponse response = tokenService.issue(
                new AuthenticatedUser(42L, "ada@example.com", UserRole.CUSTOMER));
        Jwt decoded = decoder.decode(response.accessToken());

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getClaimAsString(JwtClaims.EMAIL)).isEqualTo("ada@example.com");
        assertThat(decoded.getClaimAsString(JwtClaims.ROLE)).isEqualTo("CUSTOMER");
        assertThat(decoded.getIssuedAt()).isNotNull().isAfterOrEqualTo(before);
        assertThat(decoded.getExpiresAt()).isNotNull().isAfter(decoded.getIssuedAt());
    }

    @Test
    void expirationFollowsTheConfiguredLifetime() {
        JwtTokenService shortLived = new JwtTokenService(encoder, properties(60_000L));

        AccessTokenResponse response = shortLived.issue(
                new AuthenticatedUser(42L, "ada@example.com", UserRole.ADMIN));
        Jwt decoded = decoder.decode(response.accessToken());

        assertThat(decoded.getExpiresAt()).isEqualTo(decoded.getIssuedAt().plusSeconds(60));
        assertThat(response.expiresAt()).isEqualTo(decoded.getExpiresAt());
    }

    @Test
    void carriesTheAdministratorRoleWhenIssuedForAnAdministrator() {
        AccessTokenResponse response = tokenService.issue(
                new AuthenticatedUser(7L, "root@example.com", UserRole.ADMIN));

        assertThat(decoder.decode(response.accessToken()).getClaimAsString(JwtClaims.ROLE))
                .isEqualTo("ADMIN");
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        String foreign = encoderFor(OTHER_SECRET)
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .subject("42")
                                .issuedAt(Instant.now())
                                .expiresAt(Instant.now().plusSeconds(600))
                                .build()))
                .getTokenValue();

        assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnExpiredToken() {
        Instant issuedAt = Instant.now().minusSeconds(7_200);
        String expired = encoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .subject("42")
                                .claim(JwtClaims.ROLE, "CUSTOMER")
                                .issuedAt(issuedAt)
                                .expiresAt(issuedAt.plusSeconds(60))
                                .build()))
                .getTokenValue();

        assertThatThrownBy(() -> decoder.decode(expired))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsAMalformedToken() {
        assertThatThrownBy(() -> decoder.decode("not-a-jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("a.b.c")).isInstanceOf(JwtException.class);
    }

    @Test
    void neverPrintsTheTokenValue() {
        AccessTokenResponse response = tokenService.issue(
                new AuthenticatedUser(42L, "ada@example.com", UserRole.CUSTOMER));

        assertThat(response.toString())
                .doesNotContain(response.accessToken())
                .contains("****");
    }

    private static JwtEncoder encoderFor(String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
    }

    private static JwtDecoder decoderFor(String secret) {
        return NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static ApplicationProperties properties(long expirationMs) {
        return new ApplicationProperties(
                new ApplicationProperties.Jwt(SECRET, expirationMs),
                new ApplicationProperties.Cors(List.of("http://localhost")),
                new ApplicationProperties.Pagination(20, 100));
    }
}
