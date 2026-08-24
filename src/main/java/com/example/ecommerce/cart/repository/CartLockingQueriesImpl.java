package com.example.ecommerce.cart.repository;

import com.example.ecommerce.cart.entity.Cart;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;

public class CartLockingQueriesImpl implements CartLockingQueries {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<Cart> findWithItemsByUserIdForUpdate(Long userId) {
        List<Long> ids = entityManager
                .createQuery("select c.id from Cart c where c.user.id = :userId", Long.class)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setParameter("userId", userId)
                .getResultList();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        EntityGraph<Cart> graph = entityManager.createEntityGraph(Cart.class);
        graph.addSubgraph("items").addAttributeNodes("product");
        Cart cart = entityManager
                .createQuery("select c from Cart c where c.id = :id", Cart.class)
                .setParameter("id", ids.getFirst())
                .setHint("jakarta.persistence.fetchgraph", graph)
                .getSingleResult();
        return Optional.of(cart);
    }
}
