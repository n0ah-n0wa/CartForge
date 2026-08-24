package com.example.ecommerce.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class CorsConfigTest {

    private final CorsConfigurationSource source =
            new CorsConfig().corsConfigurationSource(properties("http://localhost"));

    @Test
    void appliesToVersionedApiPaths() {
        assertThat(configurationFor("/api/v1/orders")).isNotNull();
        assertThat(configurationFor("/api/v1/products")).isNotNull();
        assertThat(configurationFor("/api/v1/auth/login")).isNotNull();
    }

    @Test
    void allowsOnlyTheConfiguredOrigins() {
        CorsConfiguration configuration = configurationFor("/api/v1/orders");

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("http://localhost");
        assertThat(configuration.checkOrigin("http://localhost")).isEqualTo("http://localhost");
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void neverAllowsCredentials() {
        assertThat(configurationFor("/api/v1/orders").getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void refusesTheNullOrigin() {
        assertThat(configurationFor("/api/v1/orders").checkOrigin("null")).isNull();
    }

    @Test
    void doesNotApplyToActuator() {
        assertThat(configurationFor("/actuator/health")).isNull();
    }

    private CorsConfiguration configurationFor(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        return source.getCorsConfiguration(request);
    }

    private static ApplicationProperties properties(String origin) {
        return new ApplicationProperties(
                new ApplicationProperties.Jwt("test-only-jwt-secret-not-for-production", 900_000L),
                new ApplicationProperties.Cors(List.of(origin)),
                new ApplicationProperties.Pagination(20, 100),
                ApplicationProperties.RateLimit.defaults());
    }
}
