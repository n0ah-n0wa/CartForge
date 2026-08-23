package com.example.ecommerce.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.OrderStatusTransitionException;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Category CATEGORY = Category.create("Books", "books", null);

    @Test
    void startsPendingWithAZeroTotalInTheDefaultCurrency() {
        Order order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("0.00");
        assertThat(order.getCurrency()).isEqualTo(CurrencyCode.EUR);
        assertThat(order.getOrderNumber()).isEqualTo("ORD-2026-000001");
        assertThat(order.getItems()).isEmpty();
    }

    @Test
    void snapshotsProductNameSkuAndUnitPriceOnEachLine() {
        Order order = newOrder();
        Product keyboard = product("KB-001", "Keyboard", "keyboard", "49.50");

        OrderItem item = order.addItem(keyboard, 2);

        assertThat(item.getProductName()).isEqualTo("Keyboard");
        assertThat(item.getSku()).isEqualTo("KB-001");
        assertThat(item.getUnitPrice()).isEqualByComparingTo("49.50");
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getLineTotal()).isEqualByComparingTo("99.00");
        assertThat(item.getOrder()).isSameAs(order);
    }

    @Test
    void snapshotIsUnaffectedByLaterCatalogChanges() {
        Order order = newOrder();
        Product keyboard = product("KB-001", "Keyboard", "keyboard", "49.50");
        OrderItem item = order.addItem(keyboard, 2);

        keyboard.rename("Keyboard Pro", "keyboard-pro");
        keyboard.changePrice(new BigDecimal("99.99"), CurrencyCode.EUR);
        keyboard.deactivate();

        assertThat(item.getProductName()).isEqualTo("Keyboard");
        assertThat(item.getUnitPrice()).isEqualByComparingTo("49.50");
        assertThat(item.getLineTotal()).isEqualByComparingTo("99.00");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("99.00");
    }

    @Test
    void totalIsTheSumOfLineTotals() {
        Order order = newOrder();

        order.addItem(product("KB-001", "Keyboard", "keyboard", "49.50"), 2);
        order.addItem(product("MS-001", "Mouse", "mouse", "10.05"), 3);

        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("129.15");
    }

    @Test
    void rejectsNonPositiveLineQuantities() {
        Order order = newOrder();
        Product keyboard = product("KB-001", "Keyboard", "keyboard", "49.50");

        assertThatThrownBy(() -> order.addItem(keyboard, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
        assertThat(order.getItems()).isEmpty();
    }

    @Test
    void followsTheSpecifiedStatusLifecycle() {
        Order order = newOrder();

        order.transitionTo(OrderStatus.CONFIRMED);
        order.transitionTo(OrderStatus.PROCESSING);
        order.transitionTo(OrderStatus.SHIPPED);
        order.transitionTo(OrderStatus.DELIVERED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void rejectsAnInvalidTransitionAndKeepsTheCurrentStatus() {
        Order order = newOrder();
        order.transitionTo(OrderStatus.CONFIRMED);
        order.transitionTo(OrderStatus.PROCESSING);

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.PENDING))
                .isInstanceOf(OrderStatusTransitionException.class)
                .hasMessageContaining("PROCESSING")
                .hasMessageContaining("PENDING");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
    }

    @Test
    void cancelsOnlyWhileCancellationIsAllowed() {
        Order cancellable = newOrder();
        assertThat(cancellable.isCancellable()).isTrue();
        cancellable.cancel();
        assertThat(cancellable.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        Order shipped = newOrder();
        shipped.transitionTo(OrderStatus.CONFIRMED);
        shipped.transitionTo(OrderStatus.PROCESSING);
        shipped.transitionTo(OrderStatus.SHIPPED);

        assertThat(shipped.isCancellable()).isFalse();
        assertThatThrownBy(shipped::cancel).isInstanceOf(OrderStatusTransitionException.class);
    }

    @Test
    void requiresAnOrderNumberAndShippingAddress() {
        User customer = customer();

        assertThatThrownBy(() -> Order.place("  ", customer, "1 Main Street", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orderNumber");
        assertThatThrownBy(() -> Order.place("ORD-2026-000001", customer, "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shippingAddress");
    }

    private static Order newOrder() {
        return Order.place("ORD-2026-000001", customer(), "1 Main Street, Springfield", null);
    }

    private static User customer() {
        return User.registerCustomer(
                "customer@example.com", "test-only-password-hash", "Ada", "Lovelace");
    }

    private static Product product(String sku, String name, String slug, String price) {
        return Product.create(sku, name, slug, null, new BigDecimal(price), null, 10, CATEGORY);
    }
}
