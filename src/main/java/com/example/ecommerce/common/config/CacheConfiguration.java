package com.example.ecommerce.common.config;

import com.example.ecommerce.common.cache.CatalogCacheErrorHandler;
import com.example.ecommerce.common.cache.CatalogCaches;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis catalog cache customization. Failures are logged and ignored so reads
 * fall through to PostgreSQL; Redis is never the source of truth.
 */
@Configuration
public class CacheConfiguration implements CachingConfigurer {

    private static final Duration PRODUCT_TTL = Duration.ofMinutes(15);
    private static final Duration CATEGORY_TTL = Duration.ofMinutes(30);
    private static final Duration PRODUCT_SEARCH_TTL = Duration.ofMinutes(5);
    private static final Duration CATEGORY_LIST_TTL = Duration.ofMinutes(30);

    /**
     * Customizes Boot's Redis {@code CacheManager} so values are JSON-encoded DTOs
     * with deterministic {@code {cacheName}:{key}} prefixes.
     */
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis")
    RedisCacheManagerBuilderCustomizer catalogRedisCacheCustomizer(ObjectMapper objectMapper) {
        RedisCacheConfiguration defaults = baseConfiguration(objectMapper)
                .entryTtl(PRODUCT_TTL);

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put(CatalogCaches.PRODUCT, defaults.entryTtl(PRODUCT_TTL));
        perCache.put(CatalogCaches.CATEGORY, defaults.entryTtl(CATEGORY_TTL));
        perCache.put(CatalogCaches.PRODUCTS, defaults.entryTtl(PRODUCT_SEARCH_TTL));
        perCache.put(CatalogCaches.CATEGORIES, defaults.entryTtl(CATEGORY_LIST_TTL));

        return builder -> builder
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .transactionAware();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        // Fail open to PostgreSQL; on evict failure attempt a full cache clear.
        return new CatalogCacheErrorHandler();
    }

    private static RedisCacheConfiguration baseConfiguration(ObjectMapper objectMapper) {
        ObjectMapper cacheMapper = objectMapper.copy();
        // Catalog values are final records, so DefaultTyping must include them.
        // LaissezFaire + EVERYTHING would deserialize arbitrary gadgets if Redis
        // were writable by an attacker — allow only application DTOs and JDK value types.
        cacheMapper.activateDefaultTyping(
                catalogTypeValidator(),
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(cacheMapper);

        return RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(cacheName -> cacheName + ":")
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        valueSerializer))
                .disableCachingNullValues();
    }

    static PolymorphicTypeValidator catalogTypeValidator() {
        return BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.example.ecommerce.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.time.")
                .allowIfSubType(String.class)
                .allowIfSubType(Boolean.class)
                .allowIfSubType(Integer.class)
                .allowIfSubType(Long.class)
                .allowIfSubType(Double.class)
                .allowIfSubType(Float.class)
                .allowIfSubType(Short.class)
                .allowIfSubType(Byte.class)
                .allowIfSubType(Character.class)
                .build();
    }
}
