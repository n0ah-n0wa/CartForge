package com.example.ecommerce.product.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.common.persistence.CurrencyCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductTest {

    private static final Category CATEGORY = Category.create("Books", "books", null);

    @Test
    void normalizesIdentifiersMoneyAndDefaults() {
        Product product = Product.create(
                " kb-001 ",
                "  Keyboard  ",
                "Keyboard",
                "   ",
                new BigDecimal("49.5"),
                null,
                7,
                CATEGORY);

        assertThat(product.getSku()).isEqualTo("KB-001");
        assertThat(product.getName()).isEqualTo("Keyboard");
        assertThat(product.getSlug()).isEqualTo("keyboard");
        assertThat(product.getDescription()).isNull();
        assertThat(product.getPrice()).isEqualByComparingTo("49.50");
        assertThat(product.getPrice().scale()).isEqualTo(2);
        assertThat(product.getCurrency()).isEqualTo(CurrencyCode.EUR);
        assertThat(product.isActive()).isTrue();
        assertThat(product.isPurchasable()).isTrue();
    }

    @Test
    void rejectsNegativePriceAndUnrepresentableScale() {
        assertThatThrownBy(() -> newProduct(new BigDecimal("-0.01"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
        assertThatThrownBy(() -> newProduct(new BigDecimal("10.005"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal places");
    }

    @Test
    void rejectsNegativeStockAndStockGoingBelowZero() {
        assertThatThrownBy(() -> newProduct(BigDecimal.TEN, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stockQuantity");

        Product product = newProduct(BigDecimal.TEN, 2);
        assertThatThrownBy(() -> product.decreaseStock(3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(product.getStockQuantity()).isEqualTo(2);

        product.decreaseStock(2);
        product.increaseStock(5);
        assertThat(product.getStockQuantity()).isEqualTo(5);
    }

    @Test
    void inactiveOrOutOfStockProductsAreNotPurchasable() {
        Product outOfStock = newProduct(BigDecimal.TEN, 0);
        assertThat(outOfStock.isPurchasable()).isFalse();

        Product inactive = newProduct(BigDecimal.TEN, 3);
        inactive.deactivate();
        assertThat(inactive.isPurchasable()).isFalse();

        inactive.activate();
        assertThat(inactive.isPurchasable()).isTrue();
    }

    @Test
    void rejectsMalformedSkuAndSlug() {
        assertThatThrownBy(() -> Product.create(
                        "KB 001", "Keyboard", "keyboard", null, BigDecimal.TEN, null, 1, CATEGORY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sku");
        assertThatThrownBy(() -> Product.create(
                        "KB-001", "Keyboard", "Not A Slug", null, BigDecimal.TEN, null, 1, CATEGORY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slug");
    }

    @Test
    void requiresACategory() {
        assertThatThrownBy(() -> Product.create(
                        "KB-001", "Keyboard", "keyboard", null, BigDecimal.TEN, null, 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("category");
    }

    private static Product newProduct(BigDecimal price, int stockQuantity) {
        return Product.create("KB-001", "Keyboard", "keyboard", null, price, null, stockQuantity, CATEGORY);
    }
}
