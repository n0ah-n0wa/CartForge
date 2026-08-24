package com.example.ecommerce.common.observability;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Reports Redis availability without failing the application health or
 * readiness groups. Catalog cache and auth rate limiting already fail open;
 * taking the process out of rotation because Redis is down would contradict
 * that contract. Named {@code redisAvailability} so it is not suppressed by
 * {@code management.health.redis.enabled=false}, which disables Spring's
 * DOWN-on-outage Redis indicator. Details never include the Redis URL or
 * credentials.
 */
@Component
public class FailOpenRedisHealthIndicator implements HealthIndicator {

    private final ObjectProvider<RedisConnectionFactory> connectionFactories;

    public FailOpenRedisHealthIndicator(ObjectProvider<RedisConnectionFactory> connectionFactories) {
        this.connectionFactories = Objects.requireNonNull(connectionFactories, "connectionFactories");
    }

    @Override
    public Health health() {
        RedisConnectionFactory connectionFactory = connectionFactories.getIfAvailable();
        if (connectionFactory == null) {
            return Health.up().withDetail("available", false).build();
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
            return Health.up().withDetail("available", true).build();
        } catch (RuntimeException redisUnavailable) {
            return Health.up().withDetail("available", false).build();
        }
    }
}
