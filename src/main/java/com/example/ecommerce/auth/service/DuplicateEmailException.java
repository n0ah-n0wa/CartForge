package com.example.ecommerce.auth.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class DuplicateEmailException extends DomainApiException {

    public DuplicateEmailException(String email) {
        super("DUPLICATE_EMAIL", HttpStatus.CONFLICT, "Email already registered: " + email);
    }
}
