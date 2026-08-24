package com.example.ecommerce.common.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers wiring for integration tests. PostgreSQL is always real;
 * Redis is either a live container or an intentionally unused URL when Redis
 * autoconfiguration is excluded.
 */
public final class IntegrationTestContainers {

    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");
    public static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");

    /**
     * Sentinel used when Redis autoconfig is excluded. Port 0 is never a listening
     * Redis, so a misplaced exclude removal fails loudly instead of hitting a
     * developer's local Redis on 6379.
     */
    public static final String UNUSED_REDIS_URL = "redis://127.0.0.1:0";

    private IntegrationTestContainers() {
    }

    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }

    public static GenericContainer<?> redis() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }

    public static void registerPostgres(DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        registry.add("DATABASE_URL", postgres::getJdbcUrl);
        registry.add("DATABASE_USERNAME", postgres::getUsername);
        registry.add("DATABASE_PASSWORD", postgres::getPassword);
        registry.add("JWT_SECRET", () -> "test-only-jwt-secret-not-for-production");
        registry.add("CORS_ORIGINS", () -> "http://localhost");
    }

    /**
     * Registers Postgres plus a non-routable Redis URL for suites that exclude
     * Redis autoconfiguration.
     */
    public static void registerPostgresWithoutRedis(
            DynamicPropertyRegistry registry, PostgreSQLContainer<?> postgres) {
        registerPostgres(registry, postgres);
        registry.add("REDIS_URL", () -> UNUSED_REDIS_URL);
    }

    public static void registerRedis(DynamicPropertyRegistry registry, GenericContainer<?> redis) {
        registry.add(
                "REDIS_URL",
                () -> "redis://%s:%d".formatted(redis.getHost(), redis.getMappedPort(6379)));
    }
}
