package com.example.ecommerce.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional deterministic bootstrap for the {@code dev} profile only.
 */
@ConfigurationProperties(prefix = "app.seed")
public record SeedProperties(boolean enabled) {
}
