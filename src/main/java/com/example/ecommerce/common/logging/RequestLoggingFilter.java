package com.example.ecommerce.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Emits one structured access line per HTTP request. Never logs headers,
 * query strings, or bodies so Authorization tokens and passwords cannot leak.
 */
public final class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            int status = response.getStatus();
            String message = "event=http_request method={} path={} status={} durationMs={}";
            if (status >= 500) {
                log.warn(message, request.getMethod(), request.getRequestURI(), status, durationMs);
            } else {
                log.info(message, request.getMethod(), request.getRequestURI(), status, durationMs);
            }
        }
    }
}
