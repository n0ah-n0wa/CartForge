package com.example.ecommerce.common.ratelimit;

import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.exception.ApiErrorResponse;
import com.example.ecommerce.common.exception.ApiErrors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies authentication rate limits before login and registration handlers run.
 * The client key is the remote address; forwarded headers are ignored so they
 * cannot be spoofed to bypass the limiter.
 */
public final class AuthRateLimitFilter extends OncePerRequestFilter {

    static final String LOGIN_PATH = "/api/v1/auth/login";
    static final String REGISTER_PATH = "/api/v1/auth/register";

    private final AuthRateLimiter authRateLimiter;
    private final ApplicationProperties properties;
    private final ObjectWriter errorWriter;

    public AuthRateLimitFilter(
            AuthRateLimiter authRateLimiter,
            ApplicationProperties properties,
            ObjectMapper objectMapper) {
        this.authRateLimiter = authRateLimiter;
        this.properties = properties;
        this.errorWriter = Objects.requireNonNull(objectMapper, "objectMapper")
                .writerFor(ApiErrorResponse.class);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.rateLimit().auth().enabled()) {
            return true;
        }
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !LOGIN_PATH.equals(path) && !REGISTER_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String endpoint = LOGIN_PATH.equals(request.getRequestURI()) ? "login" : "register";
        String clientKey = clientKey(request);
        RateLimitDecision decision = authRateLimiter.check(endpoint, clientKey);
        if (!decision.allowed()) {
            writeTooManyRequests(request, response, decision.retryAfterSeconds());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String clientKey(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private void writeTooManyRequests(
            HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        errorWriter.writeValue(
                response.getOutputStream(),
                ApiErrors.of(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMIT_EXCEEDED",
                        "Too many requests. Try again later.",
                        request.getRequestURI()));
    }
}
