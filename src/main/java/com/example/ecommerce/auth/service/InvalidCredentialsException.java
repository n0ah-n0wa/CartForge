package com.example.ecommerce.auth.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

/** Failed login — uniform message to prevent user enumeration. */
public class InvalidCredentialsException extends DomainApiException {

    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }
}
