package com.example.ecommerce.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class FailOpenRedisHealthIndicatorTest {

    @Test
    void reportsAvailableWhenPingSucceeds() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        Health health = new FailOpenRedisHealthIndicator(provider(factory)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("available", true);
        assertThat(health.getDetails().toString()).doesNotContain("redis://");
    }

    @Test
    void staysUpWhenRedisIsUnavailable() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new IllegalStateException("redis://:secret@localhost:6379"));

        Health health = new FailOpenRedisHealthIndicator(provider(factory)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("available", false);
        assertThat(health.getDetails().toString())
                .doesNotContain("secret")
                .doesNotContain("redis://");
    }

    @Test
    void staysUpWhenRedisIsNotConfigured() {
        Health health = new FailOpenRedisHealthIndicator(provider(null)).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("available", false);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedisConnectionFactory> provider(RedisConnectionFactory factory) {
        ObjectProvider<RedisConnectionFactory> objectProvider = mock(ObjectProvider.class);
        when(objectProvider.getIfAvailable()).thenReturn(factory);
        return objectProvider;
    }
}
