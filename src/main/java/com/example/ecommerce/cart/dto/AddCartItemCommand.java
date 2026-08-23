package com.example.ecommerce.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request shape for adding a product to the authenticated customer's cart.
 * The cart owner is always taken from the principal, never from the payload.
 */
public record AddCartItemCommand(
        @NotNull Long productId,
        @Min(1) int quantity
) {
}
