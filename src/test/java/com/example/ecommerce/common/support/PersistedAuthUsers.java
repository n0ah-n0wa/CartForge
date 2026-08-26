package com.example.ecommerce.common.support;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Persists enabled users for Bearer integration tests. Synthetic JWT subjects
 * without matching rows are rejected by {@code EnabledAccountJwtAuthenticationConverter}.
 */
public final class PersistedAuthUsers {

    public static final String CUSTOMER_EMAIL = "ada@example.com";
    public static final String ADMIN_EMAIL = "root@example.com";
    private static final String PASSWORD = "test-only-Password123!";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public PersistedAuthUsers(
            UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    public User ensureCustomer() {
        return userRepository
                .findByEmailIgnoreCase(CUSTOMER_EMAIL)
                .orElseGet(() -> userRepository.saveAndFlush(User.registerCustomer(
                        CUSTOMER_EMAIL, passwordEncoder.encode(PASSWORD), "Ada", "Lovelace")));
    }

    public User ensureAdmin() {
        return userRepository
                .findByEmailIgnoreCase(ADMIN_EMAIL)
                .orElseGet(() -> userRepository.saveAndFlush(User.create(
                        ADMIN_EMAIL, passwordEncoder.encode(PASSWORD), "Root", "Admin", UserRole.ADMIN)));
    }

    public String customerBearer() {
        User user = ensureCustomer();
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), UserRole.CUSTOMER))
                .accessToken();
    }

    public String adminBearer() {
        User user = ensureAdmin();
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), UserRole.ADMIN))
                .accessToken();
    }

    public String accessToken(User user) {
        return jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .accessToken();
    }
}
