package com.example.ecommerce.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductionEnvironmentValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "change-me",
            "change-me-use-a-long-random-value",
            "test-only-jwt-secret-not-for-production",
            "placeholder",
            "password",
            "secret"
    })
    void rejectsKnownPlaceholderValues(String value) {
        assertThat(ProductionEnvironmentValidator.isUnsafePlaceholder(value)).isTrue();
    }

    @Test
    void acceptsANonPlaceholderSecret() {
        assertThat(ProductionEnvironmentValidator.isUnsafePlaceholder(
                "n9f2c8a1d4b6e0f3a7c5d1b8e6f0a2c4")).isFalse();
    }

    @Test
    void rejectPlaceholderThrowsForBlankValues() {
        assertThatThrownBy(() -> ProductionEnvironmentValidator.rejectPlaceholder("JWT_SECRET", "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }
}
