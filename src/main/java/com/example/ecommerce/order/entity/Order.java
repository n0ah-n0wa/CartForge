package com.example.ecommerce.order.entity;

import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.common.persistence.VersionedEntity;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.OrderStatusTransitionException;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregate root for a placed order. Lines are owned by the order and carry a
 * commercial snapshot, so an order stays historically correct after the catalog
 * changes. The customer is never cascaded.
 */
@Entity
@Table(name = "orders")
public class Order extends VersionedEntity {

    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_orders_users"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(
            name = "total_amount",
            nullable = false,
            precision = PersistenceConventions.MONEY_PRECISION,
            scale = PersistenceConventions.MONEY_SCALE)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "shipping_address", nullable = false, length = 1000)
    private String shippingAddress;

    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public static Order place(
            String orderNumber,
            User user,
            String shippingAddress,
            CurrencyCode currency) {
        Order order = new Order();
        order.orderNumber = requireText(orderNumber, "orderNumber");
        order.user = Objects.requireNonNull(user, "user is required");
        order.shippingAddress = requireText(shippingAddress, "shippingAddress");
        order.currency = Objects.requireNonNullElse(currency, PersistenceConventions.DEFAULT_CURRENCY);
        order.status = OrderStatus.PENDING;
        order.totalAmount = zero();
        return order;
    }

    /**
     * Copies the commercially relevant product fields onto the line. Later
     * renames, repricing, or deactivation of the product must not change what
     * the customer was charged.
     */
    public OrderItem addItem(Product product, int quantity) {
        OrderItem item = OrderItem.snapshotOf(this, product, quantity);
        items.add(item);
        recalculateTotal();
        return item;
    }

    public void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new OrderStatusTransitionException(status, target);
        }
        this.status = target;
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    public boolean isCancellable() {
        return status.isCancellable();
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public User getUser() {
        return user;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    @Override
    public String toString() {
        return "Order[id=" + getId() + ", orderNumber=" + orderNumber + ", status=" + status + "]";
    }

    private void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(OrderItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PersistenceConventions.MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(PersistenceConventions.MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
