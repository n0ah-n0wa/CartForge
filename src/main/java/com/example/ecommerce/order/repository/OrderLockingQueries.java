package com.example.ecommerce.order.repository;

import com.example.ecommerce.order.entity.Order;
import java.util.Optional;

/**
 * Locks the order row first, then loads lines. A lock plus item graph in one
 * query triggers follow-on locking on PostgreSQL and can observe a stale status.
 */
public interface OrderLockingQueries {

    Optional<Order> findWithItemsByIdAndUserIdForUpdate(Long id, Long userId);

    Optional<Order> findWithItemsByIdForUpdate(Long id);
}
