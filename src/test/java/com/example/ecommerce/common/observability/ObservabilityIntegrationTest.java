package com.example.ecommerce.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.common.config.ApplicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
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

/**
 * Verifies health probes, Prometheus metrics, and that dangerous Actuator
 * endpoints stay disabled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ObservabilityIntegrationTest {

    private static final String JWT_SECRET = "test-only-jwt-secret-not-for-production";

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
        registry.add("JWT_SECRET", () -> JWT_SECRET);
        registry.add("CORS_ORIGINS", () -> "http://localhost");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationProperties applicationProperties;

    @Test
    void applicationHealthIsUpWithoutSecrets() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertBodyHasNoSecrets(response.getBody());
        assertThat(response.getBody()).doesNotContain("available");
    }

    @Test
    void livenessDoesNotDependOnInfrastructure() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/liveness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).doesNotContain("db");
        assertThat(response.getBody()).doesNotContain("redis");
        assertBodyHasNoSecrets(response.getBody());
    }

    @Autowired
    private DataSourceHealthIndicator dataSourceHealthIndicator;

    @Test
    void readinessIncludesDatabaseAndOmitsRedis() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/readiness", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).doesNotContain("redis");
        assertBodyHasNoSecrets(response.getBody());
        assertThat(dataSourceHealthIndicator.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Autowired
    private FailOpenRedisHealthIndicator redisHealthIndicator;

    @Test
    void redisHealthIsRegisteredAndFailOpen() {
        assertThat(redisHealthIndicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(redisHealthIndicator.health().getDetails()).containsEntry("available", true);
    }

    @Test
    void prometheusExposesJvmHttpAndPoolMetrics() {
        restTemplate.getForEntity("/actuator/health", String.class);

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotBlank();
        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("jvm_threads_live_threads");
        assertThat(body).contains("http_server_requests_seconds");
        assertThat(body).contains("hikaricp_connections");
        assertBodyHasNoSecrets(body);
        assertThat(body).doesNotContain(applicationProperties.jwt().secret());
        assertThat(body).doesNotContain("JWT_SECRET");
        assertThat(body).doesNotContain("DATABASE_PASSWORD");
    }

    @Test
    void dangerousActuatorEndpointsAreNotExposed() {
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/beans", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/configprops", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/heapdump", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/threaddump", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/mappings", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/shutdown", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(environment.getProperty("management.endpoint.env.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.endpoint.env.show-values")).isEqualTo("never");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");
    }

    private void assertBodyHasNoSecrets(String body) {
        assertThat(body).doesNotContain(JWT_SECRET);
        assertThat(body).doesNotContain("DATABASE_PASSWORD");
        assertThat(body).doesNotContain("JWT_SECRET");
    }
}
