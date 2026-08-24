package com.example.ecommerce.order.repository;

import com.example.ecommerce.order.entity.Order;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;

public class OrderLockingQueriesImpl implements OrderLockingQueries {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<Order> findWithItemsByIdAndUserIdForUpdate(Long id, Long userId) {
        List<Long> ids = entityManager
                .createQuery(
                        "select o.id from Order o where o.id = :id and o.user.id = :userId",
                        Long.class)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setParameter("id", id)
                .setParameter("userId", userId)
                .getResultList();
        return loadWithItems(ids);
    }

    @Override
    public Optional<Order> findWithItemsByIdForUpdate(Long id) {
        List<Long> ids = entityManager
                .createQuery("select o.id from Order o where o.id = :id", Long.class)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setParameter("id", id)
                .getResultList();
        return loadWithItems(ids);
    }

    private Optional<Order> loadWithItems(List<Long> ids) {
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        EntityGraph<Order> graph = entityManager.createEntityGraph(Order.class);
        graph.addSubgraph("items").addAttributeNodes("product");
        Order order = entityManager
                .createQuery("select o from Order o where o.id = :id", Order.class)
                .setParameter("id", ids.getFirst())
                .setHint("jakarta.persistence.fetchgraph", graph)
                .getSingleResult();
        return Optional.of(order);
    }
}
