package com.example.ecommerce.order.repository;

/**
 * Transaction-scoped serialization for a user and {@code Idempotency-Key}.
 */
public interface CheckoutIdempotencyLockingQueries {

    void lockByUserIdAndKey(Long userId, String idempotencyKey);
}
