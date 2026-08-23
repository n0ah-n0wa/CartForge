package com.example.ecommerce.order.repository;

import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.entity.Order;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Customer-facing reads are scoped by owner id so a customer can never read
 * another customer's order. Item graphs are declared only on single-result
 * finders; combining a collection graph with {@code Pageable} would paginate
 * in memory.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNumber(String orderNumber);

    boolean existsByOrderNumber(String orderNumber);

    Optional<Order> findByOrderNumberAndUserId(String orderNumber, Long userId);

    Optional<Order> findByIdAndUserId(Long id, Long userId);

    Page<Order> findByUserId(Long userId, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsByOrderNumber(String orderNumber);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsByOrderNumberAndUserId(String orderNumber, Long userId);
}
