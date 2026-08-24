package com.example.ecommerce.cart.repository;

import com.example.ecommerce.cart.entity.Cart;
import java.util.Optional;

/**
 * Loads a cart for mutation after locking only the cart row. Combining
 * {@code PESSIMISTIC_WRITE} with an item entity graph makes PostgreSQL use
 * Hibernate follow-on locking, which can return a stale collection.
 */
public interface CartLockingQueries {

    Optional<Cart> findWithItemsByUserIdForUpdate(Long userId);
}
