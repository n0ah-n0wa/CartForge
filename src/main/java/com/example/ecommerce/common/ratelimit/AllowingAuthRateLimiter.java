package com.example.ecommerce.common.ratelimit;

/**
 * Always allows. Used when Redis is not on the classpath of a test context.
 */
public final class AllowingAuthRateLimiter implements AuthRateLimiter {

    @Override
    public RateLimitDecision check(String endpoint, String clientKey) {
        return RateLimitDecision.allow();
    }
}
