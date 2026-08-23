package com.example.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentUserProviderTest {

    private final CurrentUserProvider currentUser = new CurrentUserProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsTheUserIdEmailAndRoleFromTheToken() {
        authenticate(42L, "ada@example.com", "CUSTOMER");

        assertThat(currentUser.currentUserId()).contains(42L);
        assertThat(currentUser.currentEmail()).contains("ada@example.com");
        assertThat(currentUser.requireUserId()).isEqualTo(42L);
        assertThat(currentUser.isAdmin()).isFalse();
    }

    @Test
    void recognisesAnAdministrator() {
        authenticate(2L, "root@example.com", "ADMIN");

        assertThat(currentUser.isAdmin()).isTrue();
    }

    @Test
    void reportsNoUserWhenTheRequestIsAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(currentUser.currentUserId()).isEmpty();
        assertThat(currentUser.isAdmin()).isFalse();
        assertThatThrownBy(currentUser::requireUserId).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reportsNoUserWhenTheContextIsEmpty() {
        assertThat(currentUser.currentUserId()).isEmpty();
        assertThatThrownBy(currentUser::requireUserId).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void acceptsOwnershipOnlyForTheAuthenticatedUser() {
        authenticate(42L, "ada@example.com", "CUSTOMER");

        assertThatCode(() -> currentUser.requireSelf(42L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> currentUser.requireSelf(43L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("another user");
        assertThatThrownBy(() -> currentUser.requireSelf(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void refusesOwnershipToAnAdministratorActingOnSomeoneElsesResource() {
        authenticate(2L, "root@example.com", "ADMIN");

        assertThatThrownBy(() -> currentUser.requireSelf(42L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ignoresATokenWhoseSubjectIsNotAUserId() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                Jwt.withTokenValue("token")
                        .header("alg", "HS256")
                        .subject("not-a-number")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build(),
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));

        assertThat(currentUser.currentUserId()).isEmpty();
        assertThatThrownBy(currentUser::requireUserId).isInstanceOf(AccessDeniedException.class);
    }

    private static void authenticate(long userId, String email, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .claim(JwtClaims.EMAIL, email)
                .claim(JwtClaims.ROLE, role)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
