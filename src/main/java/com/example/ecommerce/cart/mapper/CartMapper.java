package com.example.ecommerce.cart.mapper;

import com.example.ecommerce.cart.dto.CartItemResponse;
import com.example.ecommerce.cart.dto.CartResponse;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.entity.CartItem;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.product.entity.Product;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CartMapper {

    /**
     * Reads each line's product, so the cart must be loaded with its items and
     * products fetched (see {@code findWithItemsByUserId}).
     */
    public CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(CartMapper::toItemResponse)
                .toList();

        return new CartResponse(
                cart.getId(),
                items,
                resolveCurrency(cart),
                total(items),
                cart.getTotalQuantity(),
                cart.getCreatedAt(),
                cart.getUpdatedAt());
    }

    public static CartItemResponse toItemResponse(CartItem item) {
        Product product = item.getProduct();
        BigDecimal unitPrice = product.getPrice();
        return new CartItemResponse(
                item.getId(),
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getSlug(),
                unitPrice,
                product.getCurrency(),
                item.getQuantity(),
                lineTotal(unitPrice, item.getQuantity()));
    }

    private static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(PersistenceConventions.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal total(List<CartItemResponse> items) {
        return items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(PersistenceConventions.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static CurrencyCode resolveCurrency(Cart cart) {
        return cart.getItems().stream()
                .map(item -> item.getProduct().getCurrency())
                .findFirst()
                .orElse(PersistenceConventions.DEFAULT_CURRENCY);
    }
}
