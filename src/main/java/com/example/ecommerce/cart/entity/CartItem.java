package com.example.ecommerce.cart.entity;

import com.example.ecommerce.common.persistence.BaseEntity;
import com.example.ecommerce.product.entity.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * A single cart line. Instances are created through {@link Cart} so the
 * bidirectional link and the one-line-per-product rule stay consistent.
 */
@Entity
@Table(name = "cart_items")
public class CartItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "cart_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_cart_items_carts"))
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_cart_items_products"))
    private Product product;

    @Column(nullable = false)
    private int quantity;

    protected CartItem() {
    }

    static CartItem of(Cart cart, Product product, int quantity) {
        CartItem item = new CartItem();
        item.cart = Objects.requireNonNull(cart, "cart is required");
        item.product = Objects.requireNonNull(product, "product is required");
        item.quantity = requirePositive(quantity);
        return item;
    }

    void increaseQuantity(int amount) {
        this.quantity = Math.addExact(quantity, requirePositive(amount));
    }

    void changeQuantity(int quantity) {
        this.quantity = requirePositive(quantity);
    }

    boolean references(Product other) {
        if (product == other) {
            return true;
        }
        Long productId = product.getId();
        return productId != null && productId.equals(other.getId());
    }

    public Cart getCart() {
        return cart;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    @Override
    public String toString() {
        return "CartItem[id=" + getId() + ", quantity=" + quantity + "]";
    }

    private static int requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
        return quantity;
    }
}
