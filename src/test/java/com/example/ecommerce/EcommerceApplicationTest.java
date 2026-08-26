package com.example.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.common.cache.CatalogCacheErrorHandler;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.observability.FailOpenRedisHealthIndicator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Environment environment;

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
    void connectsToPostgreSQL() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualToIgnoringCase("PostgreSQL");
        }
    }

    @Test
    void flywayAppliesTheInfrastructureBaseline() {
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("11");
        assertThat(flyway.info().current().getDescription()).isEqualTo("orders list indexes");
        assertThat(flyway.info().current().getState()).isEqualTo(MigrationState.SUCCESS);

        List<String> applied = jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success order by installed_rank",
                String.class);
        assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(jdbcTemplate.queryForObject(
                        "select obj_description('public'::regnamespace, 'pg_namespace')",
                        String.class))
                .contains("Flyway");
    }

    @Test
    void hibernateValidatesSchemaRatherThanGeneratingIt() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.hibernate.naming.physical-strategy"))
                .isEqualTo("org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
    }

    @Autowired
    private DataSourceHealthIndicator dataSourceHealthIndicator;

    @Autowired
    private FailOpenRedisHealthIndicator redisHealthIndicator;

    @Test
    void databaseAndRedisHealthContributorsAreRegistered() {
        assertThat(dataSourceHealthIndicator.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(redisHealthIndicator.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(redisHealthIndicator.health().getDetails()).containsEntry("available", true);
    }

    @Test
    void cacheFailuresAreLoggedInsteadOfFailingRequests() {
        assertThat(cacheInterceptor.getErrorHandler()).isInstanceOf(CatalogCacheErrorHandler.class);
    }

    @Test
    void bindsExternalizedApplicationSettings() {
        assertThat(applicationProperties.jwt().expirationMs()).isEqualTo(1_800_000L);
        assertThat(applicationProperties.cors().origins()).containsExactly("http://localhost");
        assertThat(applicationProperties.pagination().defaultPageSize()).isEqualTo(10);
        assertThat(applicationProperties.pagination().maxPageSize()).isEqualTo(50);
        assertThat(applicationProperties.rateLimit().auth().enabled()).isTrue();
        assertThat(applicationProperties.rateLimit().auth().limit()).isEqualTo(20);
        assertThat(applicationProperties.rateLimit().auth().windowSeconds()).isEqualTo(60);
        assertThat(applicationProperties.jwt().secret()).isNotBlank();
        assertThat(applicationProperties.jwt().toString()).doesNotContain(applicationProperties.jwt().secret());
    }
}
