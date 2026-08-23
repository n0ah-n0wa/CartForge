package com.example.ecommerce.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.dto.OrderResponse;
import com.example.ecommerce.order.dto.OrderSummaryResponse;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OrderMapperTest {

    private static final Category CATEGORY = Category.create("Books", "books", null);

    private final OrderMapper mapper = new OrderMapper();

    @Test
    void reportsTheStoredSnapshotForEachLine() {
        Order order = newOrder();
        order.addItem(product("KB-001", "Keyboard", "keyboard", "49.50"), 2);

        OrderResponse response = mapper.toResponse(order);

        assertThat(response.orderNumber()).isEqualTo("ORD-2026-000001");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.currency()).isEqualTo(CurrencyCode.EUR);
        assertThat(response.shippingAddress()).isEqualTo("1 Main Street, Springfield");
        assertThat(response.totalAmount()).isEqualByComparingTo("99.00");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).productName()).isEqualTo("Keyboard");
        assertThat(response.items().get(0).sku()).isEqualTo("KB-001");
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("49.50");
        assertThat(response.items().get(0).lineTotal()).isEqualByComparingTo("99.00");
    }

    @Test
    void summaryOmitsLinesAndShippingAddress() {
        Order order = newOrder();
        order.addItem(product("KB-001", "Keyboard", "keyboard", "49.50"), 2);

        OrderSummaryResponse summary = mapper.toSummaryResponse(order);

        assertThat(summary.orderNumber()).isEqualTo("ORD-2026-000001");
        assertThat(summary.totalAmount()).isEqualByComparingTo("99.00");
        assertThat(Arrays.stream(OrderSummaryResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .doesNotContain("items", "shippingAddress");
    }

    @Test
    void neverExposesTheUserOrProductEntities() {
        var orderComponents = Arrays.stream(OrderResponse.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();

        assertThat(orderComponents).doesNotContain(User.class, Order.class);
        assertThat(Arrays.stream(OrderResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .doesNotContain("user", "userId");
    }

    private static Order newOrder() {
        return Order.place(
                "ORD-2026-000001",
                User.registerCustomer("customer@example.com", "test-only-password-hash", "Ada", "Lovelace"),
                "1 Main Street, Springfield",
                null);
    }

    private static Product product(String sku, String name, String slug, String price) {
        return Product.create(sku, name, slug, null, new BigDecimal(price), null, 10, CATEGORY);
    }
}
