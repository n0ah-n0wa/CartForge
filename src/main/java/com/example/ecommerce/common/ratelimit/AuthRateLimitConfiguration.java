package com.example.ecommerce.common.ratelimit;

import com.example.ecommerce.common.config.ApplicationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class AuthRateLimitConfiguration {

    @Bean
    @Primary
    AuthRateLimiter authRateLimiter(
            ObjectProvider<StringRedisTemplate> redisTemplates, ApplicationProperties properties) {
        StringRedisTemplate redisTemplate = redisTemplates.getIfAvailable();
        AuthRateLimiter inner = redisTemplate == null
                ? new AllowingAuthRateLimiter()
                : new RedisFixedWindowAuthRateLimiter(redisTemplate, properties);
        return new FailOpenAuthRateLimiter(inner);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<AuthRateLimitFilter> authRateLimitFilterRegistration(
            AuthRateLimiter authRateLimiter, ApplicationProperties properties, ObjectMapper objectMapper) {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(authRateLimiter, properties, objectMapper);
        FilterRegistrationBean<AuthRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);
        return registration;
    }
}
