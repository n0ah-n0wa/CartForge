package com.example.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.auth.dto.AccessTokenResponse;
import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.dto.LoginRequest;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    private static final String PLAINTEXT = "test-only-Password123!";

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenService jwtTokenService;

    private PasswordEncoder passwordEncoder;
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        authenticationService = new AuthenticationService(userRepository, passwordEncoder, jwtTokenService);
    }

    @Test
    void returnsTheTokenClaimsForValidCredentials() {
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(customer()));

        AuthenticatedUser authenticated =
                authenticationService.authenticate(new LoginRequest("ada@example.com", PLAINTEXT));

        assertThat(authenticated.email()).isEqualTo("ada@example.com");
        assertThat(authenticated.role()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void issuesATokenForTheVerifiedPrincipal() {
        AccessTokenResponse token = new AccessTokenResponse("signed-token", "Bearer", Instant.now());
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(customer()));
        when(jwtTokenService.issue(any(AuthenticatedUser.class))).thenReturn(token);

        AccessTokenResponse issued =
                authenticationService.login(new LoginRequest("ada@example.com", PLAINTEXT));

        ArgumentCaptor<AuthenticatedUser> principal = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(jwtTokenService).issue(principal.capture());
        assertThat(principal.getValue().email()).isEqualTo("ada@example.com");
        assertThat(principal.getValue().role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(issued).isSameAs(token);
    }

    @Test
    void issuesNoTokenWhenCredentialsAreRejected() {
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(customer()));

        assertThatThrownBy(() -> authenticationService.login(new LoginRequest("ada@example.com", "wrong-Password1!")))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(jwtTokenService, never()).issue(any(AuthenticatedUser.class));
    }

    @Test
    void rejectsAnUnknownEmail() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        authenticationService.authenticate(new LoginRequest("nobody@example.com", PLAINTEXT)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsAWrongPassword() {
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(customer()));

        assertThatThrownBy(() ->
                        authenticationService.authenticate(new LoginRequest("ada@example.com", "wrong-Password123!")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void rejectsADisabledAccount() {
        User disabled = customer();
        disabled.disable();
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() ->
                        authenticationService.authenticate(new LoginRequest("ada@example.com", PLAINTEXT)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void reportsTheSameFailureForEveryRejectionSoUsersCannotBeEnumerated() {
        User disabled = customer();
        disabled.disable();
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("ada@example.com")).thenReturn(Optional.of(customer()));
        when(userRepository.findByEmailIgnoreCase("off@example.com")).thenReturn(Optional.of(disabled));

        String unknown = failureMessage("nobody@example.com", PLAINTEXT);
        String wrongPassword = failureMessage("ada@example.com", "wrong-Password123!");
        String disabledAccount = failureMessage("off@example.com", PLAINTEXT);

        assertThat(unknown).isEqualTo(wrongPassword).isEqualTo(disabledAccount);
        assertThat(unknown).doesNotContain(PLAINTEXT);
    }

    private String failureMessage(String email, String password) {
        try {
            authenticationService.authenticate(new LoginRequest(email, password));
            throw new AssertionError("Expected authentication to fail for " + email);
        } catch (InvalidCredentialsException expected) {
            return expected.getMessage();
        }
    }

    private User customer() {
        return User.registerCustomer(
                "ada@example.com", passwordEncoder.encode(PLAINTEXT), "Ada", "Lovelace");
    }
}
