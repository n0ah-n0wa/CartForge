package com.example.ecommerce.common.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 and 403 responses that name the Bearer scheme and nothing else. Spring's
 * default resource-server entry point copies the decoder exception into
 * {@code WWW-Authenticate}, which would leak expiry details, class names, and
 * other JWT internals forbidden by the specification.
 */
public final class SecurityProblemHandlers {

    private SecurityProblemHandlers() {
    }

    static AuthenticationEntryPoint unauthorized() {
        return (request, response, exception) -> write(response, HttpServletResponse.SC_UNAUTHORIZED, "Bearer");
    }

    static AccessDeniedHandler forbidden() {
        return (request, response, exception) -> write(response, HttpServletResponse.SC_FORBIDDEN, null);
    }

    private static void write(HttpServletResponse response, int status, String authenticate)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status);
        if (authenticate != null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, authenticate);
        }
        response.setContentLength(0);
    }
}
