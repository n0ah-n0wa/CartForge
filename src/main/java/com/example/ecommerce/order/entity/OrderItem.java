package com.example.ecommerce.order.entity;

import com.example.ecommerce.common.persistence.BaseEntity;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An immutable commercial snapshot of one purchased product. The columns are
 * written once at checkout and are never refreshed from the catalog; the
 * {@code product} reference exists for traceability, not for pricing.
 */
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "order_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_order_items_orders"))
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_order_items_products"))
    private Product product;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(
            name = "unit_price",
            nullable = false,
            precision = PersistenceConventions.MONEY_PRECISION,
            scale = PersistenceConventions.MONEY_SCALE)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(
            name = "line_total",
            nullable = false,
            precision = PersistenceConventions.MONEY_PRECISION,
            scale = PersistenceConventions.MONEY_SCALE)
    private BigDecimal lineTotal;

    protected OrderItem() {
    }

    static OrderItem snapshotOf(Order order, Product product, int quantity) {
        OrderItem item = new OrderItem();
        item.order = Objects.requireNonNull(order, "order is required");
        item.product = Objects.requireNonNull(product, "product is required");
        item.productName = product.getName();
        item.sku = product.getSku();
        item.unitPrice = product.getPrice();
        item.quantity = requirePositive(quantity);
        item.lineTotal = item.unitPrice
                .multiply(BigDecimal.valueOf(item.quantity))
                .setScale(PersistenceConventions.MONEY_SCALE, RoundingMode.UNNECESSARY);
        return item;
    }

    public Order getOrder() {
        return order;
    }

    public Product getProduct() {
        return product;
    }

    public String getProductName() {
        return productName;
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    @Override
    public String toString() {
        return "OrderItem[id=" + getId() + ", sku=" + sku + ", quantity=" + quantity + "]";
    }

    private static int requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        return quantity;
    }
}
