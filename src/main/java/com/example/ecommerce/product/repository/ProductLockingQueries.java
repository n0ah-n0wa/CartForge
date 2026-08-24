package com.example.ecommerce.product.repository;

import com.example.ecommerce.product.entity.Product;
import java.util.Optional;

/**
 * Locks a product row before inventory mutation. Combining
 * {@code PESSIMISTIC_WRITE} with a refresh keeps concurrent checkouts from
 * racing on {@code stock_quantity} while catalog updates continue to use
 * optimistic {@code version} checks.
 */
public interface ProductLockingQueries {

    /**
     * Acquires {@code FOR UPDATE} and reloads the product so stock and
     * {@code version} match the database. Empty when the row is missing.
     */
    Optional<Product> findByIdForUpdate(Long id);
}
