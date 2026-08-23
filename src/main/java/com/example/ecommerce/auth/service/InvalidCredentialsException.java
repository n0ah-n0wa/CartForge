package com.example.ecommerce.auth.service;

/**
 * Raised for every failed login. The message is deliberately identical whether
 * the address is unknown, the password is wrong, or the account is disabled, so
 * the API cannot be used to enumerate registered users.
 */
public class InvalidCredentialsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
