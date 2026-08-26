package com.example.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class JwtConfigTest {

    @Mock
    private UserRepository userRepository;

    private AccountSecurityService accountSecurityService;

    @BeforeEach
    void setUpAccountSecurity() {
        accountSecurityService = new AccountSecurityService(userRepository, 10);
    }

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
    void mapsAuthoritiesFromTheDatabaseRoleNotTheJwtClaim() {
        EnabledAccountJwtAuthenticationConverter converter =
                new EnabledAccountJwtAuthenticationConverter(accountSecurityService);
        stubEnabledUser(42L, UserRole.CUSTOMER);

        // Stale or elevated claim must not grant ADMIN once the DB role is CUSTOMER.
        assertThat(authorities(converter, "ADMIN")).containsExactly("ROLE_CUSTOMER");
        assertThat(authorities(converter, "CUSTOMER")).containsExactly("ROLE_CUSTOMER");

        accountSecurityService.evict(42L);
        stubEnabledUser(42L, UserRole.ADMIN);
        assertThat(authorities(converter, "CUSTOMER")).containsExactly("ROLE_ADMIN");
    }

    @Test
    void rejectsUnknownRolesAndDisabledAccounts() {
        EnabledAccountJwtAuthenticationConverter converter =
                new EnabledAccountJwtAuthenticationConverter(accountSecurityService);

        assertThatThrownBy(() -> converter.convert(tokenWithRole("42", "admin")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        assertThatThrownBy(() -> converter.convert(tokenWithRole("42", "ROLE_ADMIN")))
                .isInstanceOf(OAuth2AuthenticationException.class);

        User disabled = User.create("ada@example.com", "{bcrypt}x", "Ada", "Lovelace", UserRole.CUSTOMER);
        disabled.disable();
        when(userRepository.findById(42L)).thenReturn(Optional.of(disabled));
        assertThatThrownBy(() -> converter.convert(tokenWithRole("42", "CUSTOMER")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void tokenValidatorAcceptsOnlyKnownRoles() {
        OAuth2TokenValidator<Jwt> validator = JwtConfig.tokenValidator();

        assertThat(validator.validate(tokenWithRole("1", "CUSTOMER")).hasErrors()).isFalse();
        assertThat(validator.validate(tokenWithRole("1", "ADMIN")).hasErrors()).isFalse();
        assertThat(validator.validate(tokenWithRole("1", "admin")).hasErrors()).isTrue();
        assertThat(validator.validate(tokenWithRole("1", "ROLE_ADMIN")).hasErrors()).isTrue();
        assertThat(validator.validate(tokenWithRole("1", "SUPERUSER")).hasErrors()).isTrue();
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

    @Test
    void rejectsTokensWhoseEmailClaimDoesNotMatchTheDatabase() {
        EnabledAccountJwtAuthenticationConverter converter =
                new EnabledAccountJwtAuthenticationConverter(accountSecurityService);
        stubEnabledUser(42L, UserRole.CUSTOMER);

        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("42")
                .claim(JwtClaims.EMAIL, "wrong@example.com")
                .claim(JwtClaims.ROLE, "CUSTOMER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();

        assertThatThrownBy(() -> converter.convert(token))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("email");
    }

    private void stubEnabledUser(long id, UserRole role) {
        User user = User.create("ada@example.com", "{bcrypt}x", "Ada", "Lovelace", role);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
    }

    private static List<String> authorities(
            EnabledAccountJwtAuthenticationConverter converter, String role) {
        return converter.convert(tokenWithRole("42", role)).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private static Jwt tokenWithRole(String subject, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
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
