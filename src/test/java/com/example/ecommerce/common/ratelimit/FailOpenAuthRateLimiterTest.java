package com.example.ecommerce.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FailOpenAuthRateLimiterTest {

    @Mock
    private AuthRateLimiter delegate;

    @Test
    void allowsWhenDelegateSucceeds() {
        when(delegate.check("login", "127.0.0.1")).thenReturn(RateLimitDecision.allow());

        RateLimitDecision decision = new FailOpenAuthRateLimiter(delegate).check("login", "127.0.0.1");

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void allowsWhenDelegateThrowsWithoutPropagatingTheFailure() {
        when(delegate.check(anyString(), anyString())).thenThrow(new IllegalStateException("redis down"));

        RateLimitDecision decision = new FailOpenAuthRateLimiter(delegate).check("login", "127.0.0.1");

        assertThat(decision.allowed()).isTrue();
        verify(delegate).check("login", "127.0.0.1");
    }

    @Test
    void returnsDenyWhenDelegateDenies() {
        when(delegate.check("register", "10.0.0.1")).thenReturn(RateLimitDecision.deny(9));

        RateLimitDecision decision = new FailOpenAuthRateLimiter(delegate).check("register", "10.0.0.1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isEqualTo(9L);
        verify(delegate).check("register", "10.0.0.1");
    }
}
