package com.example.ecommerce.auth.service;

/**
 * Raised when registration is attempted for an address that already exists.
 * Maps to HTTP 409 once controllers exist. The message never carries the
 * submitted password.
 */
public class DuplicateEmailException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateEmailException(String email) {
        super("Email already registered: " + email);
    }
}
