package com.example.ecommerce.order.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

public class CheckoutIdempotencyLockingQueriesImpl implements CheckoutIdempotencyLockingQueries {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void lockByUserIdAndKey(Long userId, String idempotencyKey) {
        entityManager
                .createNativeQuery(
                        "select pg_advisory_xact_lock(hashtext(:scope), hashtext(:key))")
                .setParameter("scope", "checkout-idempotency:" + userId)
                .setParameter("key", idempotencyKey)
                .getSingleResult();
    }
}
