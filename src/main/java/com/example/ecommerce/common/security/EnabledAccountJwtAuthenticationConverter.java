package com.example.ecommerce.common.security;

import com.example.ecommerce.user.UserRole;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Converts a verified JWT into an authentication token and rejects tokens whose
 * subject user is missing or disabled. Authorities come from the database role so
 * demotion takes effect before token expiry (the signed {@code role} claim is not
 * trusted for authorization).
 */
@Component
public class EnabledAccountJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AccountSecurityService accountSecurityService;

    public EnabledAccountJwtAuthenticationConverter(AccountSecurityService accountSecurityService) {
        this.accountSecurityService = accountSecurityService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String claimRole = jwt.getClaimAsString(JwtClaims.ROLE);
        if (!JwtConfig.isKnownRole(claimRole)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "Token role is not recognized", null));
        }

        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException invalidSubject) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token", "Token subject is not a user id", null));
        }

        AccountSecurityService.AccountSecuritySnapshot snapshot =
                accountSecurityService.requireEnabledSnapshot(userId, jwt.getClaimAsString(JwtClaims.EMAIL));

        UserRole dbRole = snapshot.role();
        Collection<SimpleGrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + dbRole.name()));
        return new JwtAuthenticationToken(jwt, authorities);
    }
}
