package com.example.ecommerce.common.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionEnvironmentValidator {

    static final int MIN_JWT_SECRET_LENGTH = 32;

    private final ApplicationProperties properties;
    private final Environment environment;

    public ProductionEnvironmentValidator(ApplicationProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        require("DATABASE_URL");
        require("DATABASE_USERNAME");
        require("DATABASE_PASSWORD");
        require("REDIS_URL");
        require("JWT_SECRET");
        require("JWT_EXPIRATION");
        require("CORS_ORIGINS");

        rejectPlaceholder("JWT_SECRET", properties.jwt().secret());
        rejectShortSecret(properties.jwt().secret());
        rejectPlaceholder("DATABASE_PASSWORD", environment.getProperty("DATABASE_PASSWORD"));
        String databaseUrl = environment.getProperty("DATABASE_URL");
        rejectDevelopmentHost("DATABASE_URL", databaseUrl);
        requireDatabaseTls("DATABASE_URL", databaseUrl);
        rejectDevelopmentHost("REDIS_URL", environment.getProperty("REDIS_URL"));
        rejectWildcardCors();
    }

    private void require(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Production requires " + name + " to be set");
        }
    }

    private static void rejectDevelopmentHost(String name, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("localhost") || normalized.contains("127.0.0.1")) {
            throw new IllegalStateException("Production " + name + " must not use a development host");
        }
    }

    private void rejectWildcardCors() {
        boolean hasWildcard = properties.cors().origins().stream()
                .map(String::trim)
                .anyMatch("*"::equals);
        if (hasWildcard) {
            throw new IllegalStateException(
                    "Production CORS must use an explicit allowlist; wildcard origins are not allowed");
        }
    }

    private static void rejectShortSecret(String secret) {
        if (secret.length() < MIN_JWT_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "Production JWT_SECRET must be at least " + MIN_JWT_SECRET_LENGTH + " characters");
        }
    }

    static void rejectPlaceholder(String name, String value) {
        if (value == null || isUnsafePlaceholder(value)) {
            throw new IllegalStateException("Production " + name + " must be a real value, not a placeholder");
        }
    }

    static void requireDatabaseTls(String name, String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return;
        }
        String normalized = jdbcUrl.toLowerCase(Locale.ROOT);
        if (normalized.contains("sslmode=require")
                || normalized.contains("sslmode=verify-full")
                || normalized.contains("sslmode=verify-ca")) {
            return;
        }
        throw new IllegalStateException(
                "Production " + name + " must enforce TLS (sslmode=require, verify-ca, or verify-full)");
    }

    static boolean isUnsafePlaceholder(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty()
                || normalized.contains("change-me")
                || normalized.contains("change_me")
                || normalized.contains("placeholder")
                || normalized.contains("test-only")
                || normalized.contains("not-for-production")
                || normalized.contains("local-k8s-demo")
                || normalized.contains("demo-only")
                || normalized.contains("ci-dev-only")
                || "password".equals(normalized)
                || "secret".equals(normalized);
    }
}
