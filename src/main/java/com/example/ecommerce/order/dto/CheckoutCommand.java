package com.example.ecommerce.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Checkout request. The customer is always taken from the security context.
 */
public record CheckoutCommand(
        @NotBlank @Size(max = 1000) String shippingAddress
) {
}
