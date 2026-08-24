package com.example.ecommerce.order.dto;

/**
 * Checkout outcome. {@code replayed} is true when an {@code Idempotency-Key}
 * returned a previously committed order instead of placing a new one.
 */
public record CheckoutResult(OrderResponse order, boolean replayed) {
}
