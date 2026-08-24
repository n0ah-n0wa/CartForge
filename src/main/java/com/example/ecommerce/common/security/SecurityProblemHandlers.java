package com.example.ecommerce.common.security;

import com.example.ecommerce.common.exception.ApiErrorResponse;
import com.example.ecommerce.common.exception.ApiErrors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 401 and 403 responses that use the standard API error envelope and name the
 * Bearer scheme without copying decoder exceptions into {@code WWW-Authenticate}.
 * Spring's default resource-server entry point would otherwise leak expiry
 * details, class names, and other JWT internals forbidden by the specification.
 */
@Component
public class SecurityProblemHandlers {

    private final ObjectWriter errorWriter;

    public SecurityProblemHandlers(ObjectMapper objectMapper) {
        this.errorWriter = Objects.requireNonNull(objectMapper, "objectMapper")
                .writerFor(ApiErrorResponse.class);
    }

    public AuthenticationEntryPoint unauthorized() {
        return (request, response, exception) -> write(
                request,
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "UNAUTHORIZED",
                "Authentication is required",
                "Bearer");
    }

    public AccessDeniedHandler forbidden() {
        return (request, response, exception) -> write(
                request,
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "FORBIDDEN",
                "Access is denied",
                null);
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message,
            String authenticate)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (authenticate != null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, authenticate);
        }
        errorWriter.writeValue(
                response.getOutputStream(),
                ApiErrors.of(HttpStatus.valueOf(status), code, message, request.getRequestURI()));
    }
}
