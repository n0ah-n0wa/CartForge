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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies authentication rate limits before login and registration handlers run.
 *
 * <p>By default the client key is {@code request.getRemoteAddr()} and forwarded
 * headers are ignored (anti-spoofing). When the remote address is listed in
 * {@code app.rate-limit.auth.trusted-proxies}, the left-most
 * {@code X-Forwarded-For} hop is used so Kubernetes Ingress clients are not
 * collapsed into one shared bucket.
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

    static String clientKey(HttpServletRequest request, List<String> trustedProxies) {
        String remote = request.getRemoteAddr();
        String remoteKey = remote == null || remote.isBlank() ? "unknown" : remote.trim();
        if (!isTrustedProxy(remoteKey, trustedProxies)) {
            return remoteKey;
        }
        String forwarded = firstForwardedClient(request.getHeader("X-Forwarded-For"));
        return forwarded == null ? remoteKey : forwarded;
    }

    private String clientKey(HttpServletRequest request) {
        return clientKey(request, properties.rateLimit().auth().trustedProxies());
    }

    private static boolean isTrustedProxy(String remoteAddr, List<String> trustedProxies) {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return false;
        }
        String normalizedRemote = remoteAddr.toLowerCase(Locale.ROOT);
        for (String trusted : trustedProxies) {
            if (trusted != null && normalizedRemote.equals(trusted.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Uses the left-most hop — the original client — when the immediate peer is a
     * trusted Ingress/proxy that appends to {@code X-Forwarded-For}.
     */
    static String firstForwardedClient(String xForwardedFor) {
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return null;
        }
        String first = xForwardedFor.split(",")[0].trim();
        if (first.isEmpty()) {
            return null;
        }
        // Strip optional port / IPv6 brackets for a stable rate-limit key.
        if (first.startsWith("[") && first.contains("]")) {
            first = first.substring(1, first.indexOf(']'));
        } else if (first.contains(".") && first.contains(":")) {
            first = first.substring(0, first.indexOf(':'));
        }
        return first.isBlank() ? null : first;
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
                        request));
    }
}
