package com.example.ecommerce.inventory.service;

/**
 * Raised when an inventory quantity is not strictly positive.
 * Maps to HTTP 400 ({@code INVALID_INVENTORY_QUANTITY}).
 */
public class InvalidInventoryQuantityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidInventoryQuantityException(String message) {
        super(message);
    }
}
