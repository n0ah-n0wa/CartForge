package com.example.ecommerce.common.ratelimit;

import com.example.ecommerce.common.config.ApplicationProperties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Fixed-window counter in Redis. The first increment in a window also sets TTL.
 */
public final class RedisFixedWindowAuthRateLimiter implements AuthRateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = incrementScript();

    private final StringRedisTemplate redisTemplate;
    private final ApplicationProperties properties;

    public RedisFixedWindowAuthRateLimiter(
            StringRedisTemplate redisTemplate, ApplicationProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public RateLimitDecision check(String endpoint, String clientKey) {
        ApplicationProperties.RateLimit.Auth auth = properties.rateLimit().auth();
        String redisKey = "auth-rate:" + endpoint + ":" + clientKey;
        Long count = redisTemplate.execute(
                INCREMENT_SCRIPT, List.of(redisKey), String.valueOf(auth.windowSeconds()));
        if (count == null) {
            return RateLimitDecision.allow();
        }
        long attempts = count;
        if (attempts <= auth.limit()) {
            return RateLimitDecision.allow();
        }
        Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        long retryAfter = ttl == null || ttl < 1L ? auth.windowSeconds() : ttl;
        return RateLimitDecision.deny(retryAfter);
    }

    private static DefaultRedisScript<Long> incrementScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(
                """
                local current = redis.call('INCR', KEYS[1])
                if current == 1 then
                  redis.call('EXPIRE', KEYS[1], ARGV[1])
                end
                return current
                """);
        return script;
    }
}
