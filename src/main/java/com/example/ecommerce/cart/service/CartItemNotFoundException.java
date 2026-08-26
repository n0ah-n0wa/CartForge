package com.example.ecommerce.cart.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class CartItemNotFoundException extends DomainApiException {

    public CartItemNotFoundException(Long productId) {
        super("CART_ITEM_NOT_FOUND", HttpStatus.NOT_FOUND, "Cart item not found for product " + productId);
    }
}
