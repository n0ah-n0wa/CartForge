package com.example.ecommerce.cart.entity;

import com.example.ecommerce.common.persistence.BaseEntity;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.user.entity.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root for a customer's cart. Cart lines are owned by the cart:
 * removing a line deletes it, and deleting the cart deletes its lines. The
 * owning customer and the referenced products are never cascaded.
 */
@Entity
@Table(name = "carts")
public class Cart extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_carts_users"))
    private User user;

    @OneToMany(
            mappedBy = "cart",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<CartItem> items = new ArrayList<>();

    protected Cart() {
    }

    public static Cart forUser(User user) {
        Cart cart = new Cart();
        cart.user = Objects.requireNonNull(user, "user is required");
        return cart;
    }

    /**
     * Adding a product already in the cart increases its quantity instead of
     * creating a second line, which the unique key would reject anyway.
     */
    public CartItem addOrIncrease(Product product, int quantity) {
        Objects.requireNonNull(product, "product is required");
        requirePositive(quantity);

        Optional<CartItem> existing = findItem(product);
        if (existing.isPresent()) {
            CartItem item = existing.get();
            item.increaseQuantity(quantity);
            return item;
        }

        CartItem item = CartItem.of(this, product, quantity);
        items.add(item);
        return item;
    }

    public CartItem changeQuantity(Product product, int quantity) {
        CartItem item = findItem(product)
                .orElseThrow(() -> new IllegalArgumentException("Product is not in the cart"));
        item.changeQuantity(quantity);
        return item;
    }

    public boolean removeItem(Product product) {
        Objects.requireNonNull(product, "product is required");
        return items.removeIf(item -> item.references(product));
    }

    public void clear() {
        items.clear();
    }

    public Optional<CartItem> findItem(Product product) {
        Objects.requireNonNull(product, "product is required");
        return items.stream().filter(item -> item.references(product)).findFirst();
    }

    public User getUser() {
        return user;
    }

    public List<CartItem> getItems() {
        return List.copyOf(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int getTotalQuantity() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    @Override
    public String toString() {
        return "Cart[id=" + getId() + ", items=" + items.size() + "]";
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
    }
}
