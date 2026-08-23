package com.example.ecommerce.cart.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CartTest {

    private static final Category CATEGORY = Category.create("Books", "books", null);

    @Test
    void addingTheSameProductIncreasesQuantityInsteadOfDuplicatingTheLine() {
        Cart cart = newCart();
        Product product = product("KB-001", "keyboard");

        cart.addOrIncrease(product, 2);
        cart.addOrIncrease(product, 3);

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
        assertThat(cart.getTotalQuantity()).isEqualTo(5);
    }

    @Test
    void holdsSeparateLinesForDifferentProducts() {
        Cart cart = newCart();

        cart.addOrIncrease(product("KB-001", "keyboard"), 1);
        cart.addOrIncrease(product("MS-001", "mouse"), 4);

        assertThat(cart.getItems()).hasSize(2);
        assertThat(cart.getTotalQuantity()).isEqualTo(5);
    }

    @Test
    void rejectsNonPositiveQuantities() {
        Cart cart = newCart();
        Product product = product("KB-001", "keyboard");

        assertThatThrownBy(() -> cart.addOrIncrease(product, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");

        cart.addOrIncrease(product, 1);
        assertThatThrownBy(() -> cart.changeQuantity(product, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(1);
    }

    @Test
    void changeQuantityRequiresTheProductToBeInTheCart() {
        Cart cart = newCart();

        assertThatThrownBy(() -> cart.changeQuantity(product("KB-001", "keyboard"), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the cart");
    }

    @Test
    void removesAndClearsLines() {
        Cart cart = newCart();
        Product keyboard = product("KB-001", "keyboard");
        cart.addOrIncrease(keyboard, 1);
        cart.addOrIncrease(product("MS-001", "mouse"), 1);

        assertThat(cart.removeItem(keyboard)).isTrue();
        assertThat(cart.getItems()).hasSize(1);

        cart.clear();
        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.getTotalQuantity()).isZero();
    }

    @Test
    void linesKnowTheirOwningCart() {
        Cart cart = newCart();

        CartItem item = cart.addOrIncrease(product("KB-001", "keyboard"), 1);

        assertThat(item.getCart()).isSameAs(cart);
    }

    private static Cart newCart() {
        return Cart.forUser(User.registerCustomer(
                "customer@example.com", "test-only-password-hash", "Ada", "Lovelace"));
    }

    private static Product product(String sku, String slug) {
        return Product.create(
                sku, "Keyboard", slug, null, new BigDecimal("49.50"), null, 10, CATEGORY);
    }
}
