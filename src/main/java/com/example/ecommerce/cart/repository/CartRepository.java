package com.example.ecommerce.cart.repository;

import com.example.ecommerce.cart.entity.Cart;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Rendering a cart needs its lines and each line's product, so that read
 * declares an entity graph instead of walking lazy associations per line.
 * Mutating paths lock the cart row so concurrent adds cannot lose quantity
 * updates under read-modify-write.
 */
public interface CartRepository extends JpaRepository<Cart, Long>, CartLockingQueries {

    Optional<Cart> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    @EntityGraph(attributePaths = {"items", "items.product"})
    Optional<Cart> findWithItemsByUserId(Long userId);
}
