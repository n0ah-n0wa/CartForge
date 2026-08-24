package com.example.ecommerce.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propagates {@code X-Correlation-ID} into the response, request attribute, and
 * SLF4J MDC so every log line for the request can be correlated. Generates a
 * UUID when the client omits the header or supplies an unsafe value.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER));
        request.setAttribute(CorrelationIds.REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(CorrelationIds.HEADER, correlationId);
        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }
}
