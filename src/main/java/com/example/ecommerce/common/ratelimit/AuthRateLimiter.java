package com.example.ecommerce.common.ratelimit;

/**
 * Counts authentication attempts for a client against a named endpoint.
 */
public interface AuthRateLimiter {

    RateLimitDecision check(String endpoint, String clientKey);
}
