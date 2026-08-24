package com.example.ecommerce.order.service;

/**
 * Raised when the authenticated principal has no matching user row.
 * Maps to HTTP 401 ({@code ORDER_OWNER_NOT_FOUND}).
 */
public class OrderOwnerNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long userId;

    public OrderOwnerNotFoundException(Long userId) {
        super("Authenticated order owner was not found: " + userId);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
