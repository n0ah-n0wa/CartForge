package com.example.ecommerce.cart.dto;

import jakarta.validation.constraints.Min;

public record UpdateCartItemCommand(
        @Min(1) int quantity
) {
}
