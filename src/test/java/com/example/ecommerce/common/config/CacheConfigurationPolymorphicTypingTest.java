package com.example.ecommerce.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.product.dto.ProductCategoryResponse;
import com.example.ecommerce.product.dto.ProductResponse;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CacheConfigurationPolymorphicTypingTest {

    @Test
    void catalogValidatorDeserializesProductResponsesAndRejectsGadgets() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.activateDefaultTyping(
                CacheConfiguration.catalogTypeValidator(),
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);

        ProductResponse sample = new ProductResponse(
                1L,
                "SKU-1",
                "Name",
                "name",
                "desc",
                new BigDecimal("1.00"),
                CurrencyCode.EUR,
                1,
                true,
                true,
                0L,
                new ProductCategoryResponse(2L, "Books", "books"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));

        String json = mapper.writeValueAsString(sample);
        assertThatCode(() -> mapper.readValue(json, Object.class)).doesNotThrowAnyException();

        assertThatThrownBy(() -> mapper.readValue(
                        "{\"_class\":\"java.lang.ProcessBuilder\",\"command\":[\"x\"]}", Object.class))
                .isInstanceOf(Exception.class);
    }
}
