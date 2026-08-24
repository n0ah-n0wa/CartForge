package com.example.ecommerce.order.service;

import com.example.ecommerce.order.repository.OrderRepository;
import java.time.Clock;
import java.time.Year;
import org.springframework.stereotype.Component;

/**
 * Issues human-readable order numbers {@code ORD-YYYY-NNNNNN} using a database
 * sequence so concurrent checkouts cannot mint the same value.
 */
@Component
public class OrderNumberGenerator {

    private final OrderRepository orderRepository;
    private final Clock clock;

    public OrderNumberGenerator(OrderRepository orderRepository, Clock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    public String nextOrderNumber() {
        long sequence = orderRepository.nextOrderNumberSequence();
        int year = Year.now(clock).getValue();
        return "ORD-%d-%06d".formatted(year, sequence);
    }
}
