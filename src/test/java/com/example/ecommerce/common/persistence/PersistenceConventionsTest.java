package com.example.ecommerce.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PersistenceConventionsTest {

    @Test
    void buildsConstraintAndIndexNames() {
        assertThat(PersistenceConventions.primaryKeyName("users")).isEqualTo("pk_users");
        assertThat(PersistenceConventions.foreignKeyName("products", "categories"))
                .isEqualTo("fk_products_categories");
        assertThat(PersistenceConventions.uniqueConstraintName("cart_items", "cart_id", "product_id"))
                .isEqualTo("uq_cart_items_cart_id_product_id");
        assertThat(PersistenceConventions.checkConstraintName("products", "price_non_negative"))
                .isEqualTo("ck_products_price_non_negative");
        assertThat(PersistenceConventions.indexName("orders", "user_id")).isEqualTo("ix_orders_user_id");
        assertThat(PersistenceConventions.foreignKeyColumn("category")).isEqualTo("category_id");
    }

    @Test
    void moneyUsesFixedPrecisionAndEuroDefault() {
        assertThat(PersistenceConventions.MONEY_PRECISION).isEqualTo(19);
        assertThat(PersistenceConventions.MONEY_SCALE).isEqualTo(2);
        assertThat(PersistenceConventions.DEFAULT_CURRENCY).isEqualTo(CurrencyCode.EUR);
    }

    @Test
    void rejectsUnsafeIdentifierFragments() {
        assertThatThrownBy(() -> PersistenceConventions.primaryKeyName("Users;Drop"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PersistenceConventions.uniqueConstraintName("users"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
