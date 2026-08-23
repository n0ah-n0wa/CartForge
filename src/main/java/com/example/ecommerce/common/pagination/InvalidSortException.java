package com.example.ecommerce.common.pagination;

/**
 * Raised when a client requests a sort field or direction that is not allowed.
 * Maps to HTTP 400 so callers can correct the query without probing the schema.
 */
public class InvalidSortException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidSortException(String message) {
        super(message);
    }
}
