package com.example.ecommerce.cart.service;

import com.example.ecommerce.cart.entity.Cart;

/**
 * Checkout-facing cart operations. The order module must use this port instead of
 * reaching into {@link com.example.ecommerce.cart.repository.CartRepository}.
 */
public interface CartCheckoutPort {

    /**
     * Returns the customer's cart locked for update with items and products loaded.
     *
     * @throws EmptyCartException when no cart exists or the cart has no lines
     */
    Cart requireNonEmptyCartForCheckout(long userId);

    /**
     * Clears all lines after order lines are persisted. Must run in the same
     * transaction as checkout.
     */
    void clearAfterCheckout(long userId);
}
