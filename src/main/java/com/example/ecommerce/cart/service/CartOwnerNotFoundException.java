package com.example.ecommerce.cart.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class CartOwnerNotFoundException extends DomainApiException {

    public CartOwnerNotFoundException(long userId) {
        super("CART_OWNER_NOT_FOUND", HttpStatus.UNAUTHORIZED, "Cart owner " + userId + " was not found");
    }
}
