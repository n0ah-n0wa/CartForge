package com.example.ecommerce.cart.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.cart.dto.CartResponse;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CartMapperTest {

    private static final Category CATEGORY = Category.create("Books", "books", null);

    private final CartMapper mapper = new CartMapper();

    @Test
    void reportsUnitPriceLineTotalAndCartTotal() {
        Cart cart = newCart();
        cart.addOrIncrease(product("KB-001", "keyboard", "49.50"), 2);
        cart.addOrIncrease(product("MS-001", "mouse", "10.05"), 3);

        CartResponse response = mapper.toResponse(cart);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("49.50");
        assertThat(response.items().get(0).lineTotal()).isEqualByComparingTo("99.00");
        assertThat(response.items().get(1).lineTotal()).isEqualByComparingTo("30.15");
        assertThat(response.total()).isEqualByComparingTo("129.15");
        assertThat(response.totalQuantity()).isEqualTo(5);
        assertThat(response.currency()).isEqualTo(CurrencyCode.EUR);
    }

    @Test
    void reportsProductInformationForEachLine() {
        Cart cart = newCart();
        cart.addOrIncrease(product("KB-001", "keyboard", "49.50"), 1);

        var item = mapper.toResponse(cart).items().get(0);

        assertThat(item.sku()).isEqualTo("KB-001");
        assertThat(item.name()).isEqualTo("Keyboard");
        assertThat(item.slug()).isEqualTo("keyboard");
        assertThat(item.quantity()).isEqualTo(1);
    }

    @Test
    void anEmptyCartTotalsToZeroInTheDefaultCurrency() {
        CartResponse response = mapper.toResponse(newCart());

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isEqualByComparingTo("0.00");
        assertThat(response.totalQuantity()).isZero();
        assertThat(response.currency()).isEqualTo(CurrencyCode.EUR);
    }

    private static Cart newCart() {
        return Cart.forUser(User.registerCustomer(
                "customer@example.com", "test-only-password-hash", "Ada", "Lovelace"));
    }

    private static Product product(String sku, String slug, String price) {
        return Product.create(
                sku, "Keyboard", slug, null, new BigDecimal(price), null, 10, CATEGORY);
    }
}
