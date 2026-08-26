package com.example.ecommerce.user.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class UserNotFoundException extends DomainApiException {

    public UserNotFoundException(long userId) {
        super("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "User not found: " + userId);
    }
}
