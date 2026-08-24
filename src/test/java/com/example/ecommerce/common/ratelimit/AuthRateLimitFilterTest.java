package com.example.ecommerce.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.exception.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class AuthRateLimitFilterTest {

    @Mock
    private AuthRateLimiter authRateLimiter;

    @Mock
    private FilterChain filterChain;

    private ApplicationProperties properties;
    private AuthRateLimitFilter filter;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        properties = new ApplicationProperties(
                new ApplicationProperties.Jwt("test-only-jwt-secret-not-for-production-use", 3_600_000L),
                new ApplicationProperties.Cors(List.of("http://localhost")),
                new ApplicationProperties.Pagination(20, 100),
                new ApplicationProperties.RateLimit(new ApplicationProperties.RateLimit.Auth(true, 5, 60)));
        filter = new AuthRateLimitFilter(authRateLimiter, properties, objectMapper);
    }

    @Test
    void ignoresNonAuthPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(authRateLimiter);
    }

    @Test
    void writesTooManyRequestsWithoutLeakingClientDetails() throws Exception {
        when(authRateLimiter.check(eq("login"), any())).thenReturn(RateLimitDecision.deny(17));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("17");
        ApiErrorResponse body = objectMapper.readValue(response.getContentAsByteArray(), ApiErrorResponse.class);
        assertThat(body.code()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.message()).doesNotContain("203.0.113.10");
        verifyNoInteractions(filterChain);
        ArgumentCaptor<String> clientCaptor = ArgumentCaptor.forClass(String.class);
        verify(authRateLimiter).check(eq("login"), clientCaptor.capture());
        assertThat(clientCaptor.getValue()).isEqualTo("203.0.113.10");
    }
}
