package com.example.ecommerce.common.ratelimit;

/**
 * Outcome of an authentication rate-limit check. {@code retryAfterSeconds} is
 * meaningful only when the request is denied.
 */
public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, 0L);
    }

    public static RateLimitDecision deny(long retryAfterSeconds) {
        return new RateLimitDecision(false, Math.max(1L, retryAfterSeconds));
    }
}
