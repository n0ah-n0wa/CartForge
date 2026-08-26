package com.example.ecommerce.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.common.config.ApplicationProperties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RedisFixedWindowAuthRateLimiterNullResponseTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisFixedWindowAuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties(
                new ApplicationProperties.Jwt("test-only-jwt-secret-not-for-production-use", 3_600_000L),
                new ApplicationProperties.Cors(List.of("http://localhost")),
                new ApplicationProperties.Pagination(20, 100),
                new ApplicationProperties.RateLimit(new ApplicationProperties.RateLimit.Auth(true, 3, 60, List.of())));
        limiter = new RedisFixedWindowAuthRateLimiter(redisTemplate, properties);
    }

    @Test
    void allowsWhenRedisReturnsNoCount() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);

        RateLimitDecision decision = limiter.check("login", "203.0.113.10");

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void usesWindowSecondsWhenTtlIsMissingAfterADeny() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(4L);
        when(redisTemplate.getExpire(eq("auth-rate:login:203.0.113.10"), eq(TimeUnit.SECONDS))).thenReturn(null);

        RateLimitDecision decision = limiter.check("login", "203.0.113.10");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(60L);
        verify(redisTemplate).getExpire("auth-rate:login:203.0.113.10", TimeUnit.SECONDS);
    }

    @Test
    void usesWindowSecondsWhenTtlIsNonPositive() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(5L);
        when(redisTemplate.getExpire(eq("auth-rate:login:10.0.0.1"), eq(TimeUnit.SECONDS))).thenReturn(0L);

        RateLimitDecision decision = limiter.check("login", "10.0.0.1");

        assertThat(decision.retryAfterSeconds()).isEqualTo(60L);
    }
}
