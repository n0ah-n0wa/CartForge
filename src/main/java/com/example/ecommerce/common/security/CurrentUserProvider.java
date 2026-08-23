package com.example.ecommerce.common.security;

import com.example.ecommerce.user.UserRole;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The single source of ownership. Callers must resolve the acting user through
 * this provider rather than from a request body, path variable, or query
 * parameter, so a client cannot nominate someone else as the owner of a cart or
 * an order.
 */
@Component
public class CurrentUserProvider {

    public Optional<Long> currentUserId() {
        return currentToken()
                .map(Jwt::getSubject)
                .flatMap(CurrentUserProvider::parseId);
    }

    public Optional<String> currentEmail() {
        return currentToken().map(token -> token.getClaimAsString(JwtClaims.EMAIL));
    }

    /**
     * @throws AccessDeniedException when the request carries no authenticated user
     */
    public long requireUserId() {
        return currentUserId().orElseThrow(() -> new AccessDeniedException("No authenticated user"));
    }

    public boolean isAdmin() {
        return hasRole(UserRole.ADMIN);
    }

    /**
     * Rejects the request unless the authenticated user owns the resource.
     * Administrators are not exempt: cross-user reads belong on the
     * administrative endpoints, not on a customer's own path.
     */
    public void requireSelf(Long ownerId) {
        if (ownerId == null || ownerId != requireUserId()) {
            throw new AccessDeniedException("Resource belongs to another user");
        }
    }

    private boolean hasRole(UserRole role) {
        String authority = "ROLE_" + role.name();
        return authentication()
                .<Collection<? extends GrantedAuthority>>map(Authentication::getAuthorities)
                .orElseGet(List::of)
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private Optional<Jwt> currentToken() {
        return authentication()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(JwtAuthenticationToken.class::cast)
                .map(JwtAuthenticationToken::getToken);
    }

    private static Optional<Authentication> authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.of(authentication);
    }

    private static Optional<Long> parseId(String subject) {
        try {
            return Optional.of(Long.valueOf(subject));
        } catch (NumberFormatException notAUserId) {
            return Optional.empty();
        }
    }
}
