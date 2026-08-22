package com.example.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.common.config.ApplicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class EcommerceApplicationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DATABASE_USERNAME", POSTGRES::getUsername);
        registry.add("DATABASE_PASSWORD", POSTGRES::getPassword);
        registry.add(
                "REDIS_URL",
                () -> "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getMappedPort(6379)));
        registry.add("JWT_SECRET", () -> "test-only-jwt-secret-not-for-production");
        registry.add("CORS_ORIGINS", () -> "http://localhost");
        registry.add("APP_PAGINATION_DEFAULT_PAGE_SIZE", () -> "10");
        registry.add("APP_PAGINATION_MAX_PAGE_SIZE", () -> "50");
        registry.add("JWT_EXPIRATION", () -> "1800000");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ApplicationProperties applicationProperties;

    @Autowired
    private CacheInterceptor cacheInterceptor;

    @Test
    void contextLoads() {
        assertThat(restTemplate).isNotNull();
    }

    @Test
    void healthEndpointIsReachable() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).doesNotContain("redis");
    }

    @Test
    void kubernetesProbesAreReachable() {
        ResponseEntity<String> liveness = restTemplate.getForEntity("/actuator/health/liveness", String.class);
        ResponseEntity<String> readiness = restTemplate.getForEntity("/actuator/health/readiness", String.class);

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).doesNotContain("redis");
    }

    @Test
    void cacheFailuresAreLoggedInsteadOfFailingRequests() {
        assertThat(cacheInterceptor.getErrorHandler()).isInstanceOf(LoggingCacheErrorHandler.class);
    }

    @Test
    void bindsExternalizedApplicationSettings() {
        assertThat(applicationProperties.jwt().expirationMs()).isEqualTo(1_800_000L);
        assertThat(applicationProperties.cors().origins()).containsExactly("http://localhost");
        assertThat(applicationProperties.pagination().defaultPageSize()).isEqualTo(10);
        assertThat(applicationProperties.pagination().maxPageSize()).isEqualTo(50);
        assertThat(applicationProperties.jwt().secret()).isNotBlank();
        assertThat(applicationProperties.jwt().toString()).doesNotContain(applicationProperties.jwt().secret());
    }
}
