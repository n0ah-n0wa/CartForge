package com.example.ecommerce.order.repository;

import com.example.ecommerce.order.entity.CheckoutIdempotencyKey;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckoutIdempotencyKeyRepository
        extends JpaRepository<CheckoutIdempotencyKey, Long>, CheckoutIdempotencyLockingQueries {

    @EntityGraph(attributePaths = "order")
    Optional<CheckoutIdempotencyKey> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
