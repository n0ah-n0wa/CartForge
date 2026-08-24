package com.example.ecommerce.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.common.config.ApplicationProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisFixedWindowAuthRateLimiterTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private RedisFixedWindowAuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        template.getConnectionFactory().getConnection().serverCommands().flushAll();

        ApplicationProperties properties = new ApplicationProperties(
                new ApplicationProperties.Jwt("test-only-jwt-secret-not-for-production-use", 3_600_000L),
                new ApplicationProperties.Cors(List.of("http://localhost")),
                new ApplicationProperties.Pagination(20, 100),
                new ApplicationProperties.RateLimit(new ApplicationProperties.RateLimit.Auth(true, 3, 60)));
        limiter = new RedisFixedWindowAuthRateLimiter(template, properties);
    }

    @Test
    void allowsUpToTheConfiguredLimitThenDenies() {
        assertThat(limiter.check("login", "client-a").allowed()).isTrue();
        assertThat(limiter.check("login", "client-a").allowed()).isTrue();
        assertThat(limiter.check("login", "client-a").allowed()).isTrue();
        RateLimitDecision denied = limiter.check("login", "client-a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThan(0L);
    }

    @Test
    void isolatesEndpointsAndClients() {
        assertThat(limiter.check("login", "client-a").allowed()).isTrue();
        assertThat(limiter.check("login", "client-a").allowed()).isTrue();
        assertThat(limiter.check("login", "client-a").allowed()).isTrue();
        assertThat(limiter.check("login", "client-a").allowed()).isFalse();

        assertThat(limiter.check("login", "client-b").allowed()).isTrue();
        assertThat(limiter.check("register", "client-a").allowed()).isTrue();
    }
}
