package com.example.ecommerce.common.security;

import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads account security state for JWT conversion with a short in-memory TTL so
 * every authenticated request does not hit PostgreSQL. Disabled accounts are
 * never cached; demotion takes effect after at most {@code ttl}.
 */
@Service
public class AccountSecurityService {

    record AccountSecuritySnapshot(String email, UserRole role, boolean enabled) {}

    private final UserRepository userRepository;
    private final Duration ttl;
    private final ConcurrentHashMap<Long, CachedSnapshot> cache = new ConcurrentHashMap<>();

    public AccountSecurityService(
            UserRepository userRepository,
            @Value("${app.security.account-cache-ttl-seconds:10}") long ttlSeconds) {
        this.userRepository = userRepository;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    @Transactional(readOnly = true)
    public AccountSecuritySnapshot requireEnabledSnapshot(long userId, String jwtEmailClaim) {
        AccountSecuritySnapshot snapshot = loadSnapshot(userId);
        if (!emailsMatch(jwtEmailClaim, snapshot.email())) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "Token email does not match the account", null));
        }
        if (!snapshot.enabled()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "User account is disabled", null));
        }
        return snapshot;
    }

    public void evict(long userId) {
        cache.remove(userId);
    }

    private AccountSecuritySnapshot loadSnapshot(long userId) {
        CachedSnapshot cached = cache.get(userId);
        if (cached != null && !cached.isExpired(ttl)) {
            return cached.snapshot();
        }
        User user = userRepository
                .findById(userId)
                .orElseThrow(() -> new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token", "Token subject is unknown", null)));
        AccountSecuritySnapshot snapshot =
                new AccountSecuritySnapshot(user.getEmail(), user.getRole(), user.isEnabled());
        if (user.isEnabled()) {
            cache.put(userId, new CachedSnapshot(snapshot, Instant.now()));
        } else {
            cache.remove(userId);
        }
        return snapshot;
    }

    private static boolean emailsMatch(String jwtEmail, String dbEmail) {
        if (jwtEmail == null || jwtEmail.isBlank()) {
            return false;
        }
        return jwtEmail.trim().equalsIgnoreCase(Objects.requireNonNullElse(dbEmail, "").trim());
    }

    private record CachedSnapshot(AccountSecuritySnapshot snapshot, Instant loadedAt) {

        boolean isExpired(Duration ttl) {
            return loadedAt.plus(ttl).isBefore(Instant.now());
        }
    }
}
