package com.example.ecommerce.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ConfigurableApplicationContext;

class ProdConfigurationTest {

    private static final String INFRA_EXCLUDES = String.join(",",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration");

    @Test
    void prodProfileStartsWhenRequiredEnvironmentIsPresent() {
        try (ConfigurableApplicationContext context = runProd(validProdEnvironment())) {
            ApplicationProperties properties = context.getBean(ApplicationProperties.class);
            assertThat(properties.cors().origins()).containsExactly("https://shop.example.com");
            assertThat(properties.jwt().expirationMs()).isEqualTo(900_000L);
            assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("true");
        }
    }

    @Test
    void prodProfileFailsWhenJwtSecretIsMissing() {
        Map<String, Object> environment = validProdEnvironment();
        environment.put("JWT_SECRET", "");

        assertThatThrownBy(() -> runProd(environment).close())
                .hasStackTraceContaining("secret");
    }

    @Test
    void prodProfileFailsWhenDatabasePasswordIsMissing() {
        Map<String, Object> environment = validProdEnvironment();
        environment.put("DATABASE_PASSWORD", "");

        assertThatThrownBy(() -> runProd(environment).close())
                .hasStackTraceContaining("DATABASE_PASSWORD");
    }

    @Test
    void prodProfileRejectsWildcardCors() {
        Map<String, Object> environment = validProdEnvironment();
        environment.put("CORS_ORIGINS", "*");

        assertThatThrownBy(() -> runProd(environment).close())
                .hasStackTraceContaining("wildcard");
    }

    @Test
    void prodProfileRejectsPlaceholderJwtSecret() {
        Map<String, Object> environment = validProdEnvironment();
        environment.put("JWT_SECRET", "change-me-use-a-long-random-value");

        assertThatThrownBy(() -> runProd(environment).close())
                .hasStackTraceContaining("JWT_SECRET");
    }

    @Test
    void prodProfileDoesNotUseDevelopmentDatabaseFallback() {
        Map<String, Object> environment = validProdEnvironment();
        environment.put("DATABASE_URL", "");

        assertThatThrownBy(() -> runProd(environment).close())
                .hasStackTraceContaining("DATABASE_URL");
    }

    @Test
    void prodProfileRejectsLocalhostDatabaseUrl() {
        Map<String, Object> environment = validProdEnvironment();
        environment.put("DATABASE_URL", "jdbc:postgresql://localhost:5432/ecommerce");

        assertThatThrownBy(() -> runProd(environment).close())
                .hasStackTraceContaining("development host");
    }

    private static ConfigurableApplicationContext runProd(Map<String, Object> environment) {
        SpringApplication application = new SpringApplication(IsolatedProdApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("prod");

        List<String> arguments = new ArrayList<>();
        arguments.add("--spring.cache.type=simple");
        arguments.add("--spring.autoconfigure.exclude=" + INFRA_EXCLUDES);
        arguments.add("--management.endpoint.health.group.readiness.include=readinessState");
        environment.forEach((name, value) -> arguments.add("--" + name + "=" + value));
        return application.run(arguments.toArray(String[]::new));
    }

    private static Map<String, Object> validProdEnvironment() {
        Map<String, Object> environment = new HashMap<>();
        environment.put("DATABASE_URL", "jdbc:postgresql://prod-db.internal:5432/ecommerce");
        environment.put("DATABASE_USERNAME", "ecommerce");
        environment.put("DATABASE_PASSWORD", "prod-database-password-value");
        environment.put("REDIS_URL", "redis://prod-redis.internal:6379");
        environment.put("JWT_SECRET", "n9f2c8a1d4b6e0f3a7c5d1b8e6f0a2c4");
        environment.put("JWT_EXPIRATION", "900000");
        environment.put("CORS_ORIGINS", "https://shop.example.com");
        environment.put("LOGGING_LEVEL_ROOT", "WARN");
        return environment;
    }

    @SpringBootApplication(scanBasePackages = "com.example.ecommerce.common")
    @EnableCaching
    @EnableConfigurationProperties(ApplicationProperties.class)
    static class IsolatedProdApplication {
    }
}
