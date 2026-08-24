package com.example.ecommerce.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows the request when the delegate fails so Redis outages cannot take
 * authentication offline. Fail-open is an availability trade-off: attackers
 * can burst while Redis is down.
 */
public final class FailOpenAuthRateLimiter implements AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FailOpenAuthRateLimiter.class);

    private final AuthRateLimiter delegate;

    public FailOpenAuthRateLimiter(AuthRateLimiter delegate) {
        this.delegate = delegate;
    }

    @Override
    public RateLimitDecision check(String endpoint, String clientKey) {
        try {
            return delegate.check(endpoint, clientKey);
        } catch (RuntimeException exception) {
            log.warn(
                    "event=auth_rate_limit_fail_open endpoint={} cause={}",
                    endpoint,
                    exception.getClass().getSimpleName());
            return RateLimitDecision.allow();
        }
    }
}
