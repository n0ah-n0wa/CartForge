package com.example.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.common.config.ApplicationProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

class JwtConfigTest {

    private final JwtConfig jwtConfig = new JwtConfig();

    @Test
    void rejectsASigningSecretTooShortForHs256() {
        assertThatThrownBy(() -> JwtConfig.signingKey(propertiesWithSecret("a".repeat(31))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void acceptsASecretOfExactlyTheMinimumLength() {
        assertThatCode(() -> JwtConfig.signingKey(propertiesWithSecret("a".repeat(32))))
                .doesNotThrowAnyException();
    }

    @Test
    void theSigningKeyIsNeverDerivedFromADefault() {
        assertThat(JwtConfig.signingKey(propertiesWithSecret("b".repeat(40))).getEncoded())
                .isEqualTo("b".repeat(40).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void mapsTheSignedRoleClaimToAnAuthority() {
        JwtAuthenticationConverter converter = jwtConfig.jwtAuthenticationConverter();

        assertThat(authorities(converter, "ADMIN")).containsExactly("ROLE_ADMIN");
        assertThat(authorities(converter, "CUSTOMER")).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void grantsNothingWhenTheRoleClaimIsAbsentBlankOrUnknown() {
        JwtAuthenticationConverter converter = jwtConfig.jwtAuthenticationConverter();

        assertThat(authorities(converter, null)).isEmpty();
        assertThat(authorities(converter, "   ")).isEmpty();
        assertThat(authorities(converter, "admin")).isEmpty();
        assertThat(authorities(converter, "ROLE_ADMIN")).isEmpty();
        assertThat(authorities(converter, "SUPERUSER")).isEmpty();
    }

    @Test
    void tokenValidatorAcceptsOnlyKnownRoles() {
        OAuth2TokenValidator<Jwt> validator = JwtConfig.tokenValidator();

        assertThat(validator.validate(tokenWithRole("CUSTOMER")).hasErrors()).isFalse();
        assertThat(validator.validate(tokenWithRole("ADMIN")).hasErrors()).isFalse();
        assertThat(validator.validate(tokenWithRole("admin")).hasErrors()).isTrue();
        assertThat(validator.validate(tokenWithRole("ROLE_ADMIN")).hasErrors()).isTrue();
        assertThat(validator.validate(tokenWithRole("SUPERUSER")).hasErrors()).isTrue();
    }

    @Test
    void isKnownRoleAcceptsOnlyTheEnumeratedRoles() {
        assertThat(JwtConfig.isKnownRole("CUSTOMER")).isTrue();
        assertThat(JwtConfig.isKnownRole("ADMIN")).isTrue();
        assertThat(JwtConfig.isKnownRole("admin")).isFalse();
        assertThat(JwtConfig.isKnownRole("ROLE_ADMIN")).isFalse();
        assertThat(JwtConfig.isKnownRole(null)).isFalse();
        assertThat(JwtConfig.isKnownRole("  ")).isFalse();
    }

    private static List<String> authorities(JwtAuthenticationConverter converter, String role) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("42");
        if (role != null) {
            builder.claim(JwtClaims.ROLE, role);
        }
        return converter.convert(builder.build()).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private static Jwt tokenWithRole(String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("1")
                .claim(JwtClaims.EMAIL, "ada@example.com")
                .claim(JwtClaims.ROLE, role)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
    }

    private static ApplicationProperties propertiesWithSecret(String secret) {
        return new ApplicationProperties(
                new ApplicationProperties.Jwt(secret, 900_000L),
                new ApplicationProperties.Cors(List.of("http://localhost")),
                new ApplicationProperties.Pagination(20, 100),
                ApplicationProperties.RateLimit.defaults());
    }
}
