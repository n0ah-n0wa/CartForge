package com.example.ecommerce.auth.service;

import com.example.ecommerce.auth.dto.AccessTokenResponse;
import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.dto.LoginRequest;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    /**
     * Compared against when no user matches, so an unknown address costs the same
     * hashing work as a wrong password and cannot be spotted by response timing.
     */
    private final String decoyHash;

    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Verifies credentials and issues an access token.
     */
    @Transactional(readOnly = true)
    public AccessTokenResponse login(LoginRequest request) {
        AuthenticatedUser user = authenticate(request);
        AccessTokenResponse token = jwtTokenService.issue(user);
        log.info("event=authentication_succeeded userId={}", user.userId());
        return token;
    }

    /**
     * Verifies credentials and returns the claims a token will later carry.
     * Every failure path raises the same exception: an unknown address, a wrong
     * password, and a disabled account must be indistinguishable to a caller.
     */
    @Transactional(readOnly = true)
    public AuthenticatedUser authenticate(LoginRequest request) {
        Optional<User> candidate = userRepository.findByEmailIgnoreCase(request.email());

        if (candidate.isEmpty()) {
            passwordEncoder.matches(request.password(), decoyHash);
            reject(request);
        }

        User user = candidate.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            reject(request);
        }
        if (!user.isEnabled()) {
            reject(request);
        }

        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
    }

    /**
     * Every failure is logged the same way and raised as the same exception so
     * neither the API nor the log line distinguishes unknown, wrong, or disabled.
     * The password is never written.
     */
    private static void reject(LoginRequest request) {
        log.info("event=authentication_failed email={}", request.email());
        throw new InvalidCredentialsException();
    }
}
