package com.example.ecommerce.product.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.product.dto.CreateProductCommand;
import com.example.ecommerce.product.dto.ProductResponse;
import com.example.ecommerce.product.dto.UpdateProductCommand;
import com.example.ecommerce.product.entity.Product;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductMapperTest {

    private final ProductMapper mapper = new ProductMapper();

    @Test
    void mapsCreateCommandToActiveProductWithDefaultCurrency() {
        Category category = Category.create("Books", "books", null);

        Product product = mapper.toEntity(
                new CreateProductCommand(
                        "kb-001",
                        "Keyboard",
                        "keyboard",
                        "Mechanical",
                        new BigDecimal("49.50"),
                        null,
                        7,
                        1L),
                category);

        assertThat(product.getSku()).isEqualTo("KB-001");
        assertThat(product.getCurrency()).isEqualTo(CurrencyCode.EUR);
        assertThat(product.getStockQuantity()).isEqualTo(7);
        assertThat(product.getCategory()).isSameAs(category);
        assertThat(product.isActive()).isTrue();
    }

    @Test
    void appliesUpdateIncludingCategoryReassignmentAndDeactivation() {
        Category books = Category.create("Books", "books", null);
        Category media = Category.create("Media", "media", null);
        Product product = mapper.toEntity(
                new CreateProductCommand(
                        "KB-001", "Keyboard", "keyboard", "Mechanical",
                        new BigDecimal("49.50"), null, 7, 1L),
                books);

        mapper.apply(
                new UpdateProductCommand(
                        "Keyboard Pro",
                        "keyboard-pro",
                        null,
                        new BigDecimal("59.00"),
                        CurrencyCode.EUR,
                        0,
                        2L,
                        false),
                product,
                media);

        assertThat(product.getName()).isEqualTo("Keyboard Pro");
        assertThat(product.getSlug()).isEqualTo("keyboard-pro");
        assertThat(product.getDescription()).isNull();
        assertThat(product.getPrice()).isEqualByComparingTo("59.00");
        assertThat(product.getStockQuantity()).isZero();
        assertThat(product.getCategory()).isSameAs(media);
        assertThat(product.isActive()).isFalse();
        assertThat(product.isPurchasable()).isFalse();
    }

    @Test
    void mapsEntityToResponseWithCategorySummary() {
        Category category = Category.create("Books", "books", null);
        Product product = mapper.toEntity(
                new CreateProductCommand(
                        "KB-001", "Keyboard", "keyboard", "Mechanical",
                        new BigDecimal("49.50"), null, 7, 1L),
                category);

        ProductResponse response = mapper.toResponse(product);

        assertThat(response.sku()).isEqualTo("KB-001");
        assertThat(response.price()).isEqualByComparingTo("49.50");
        assertThat(response.currency()).isEqualTo(CurrencyCode.EUR);
        assertThat(response.purchasable()).isTrue();
        assertThat(response.category().name()).isEqualTo("Books");
        assertThat(response.category().slug()).isEqualTo("books");
    }
}
