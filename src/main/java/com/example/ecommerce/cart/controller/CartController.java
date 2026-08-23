package com.example.ecommerce.cart.controller;

import com.example.ecommerce.cart.dto.AddCartItemCommand;
import com.example.ecommerce.cart.dto.CartResponse;
import com.example.ecommerce.cart.dto.UpdateCartItemCommand;
import com.example.ecommerce.cart.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated customer's cart. Ownership is taken only from the security
 * context; clients never supply a user id.
 */
@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Cart", description = "Authenticated customer shopping cart")
@SecurityRequirement(name = "bearerAuth")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(
            summary = "Retrieve the current cart",
            description = "Returns the authenticated customer's cart. An empty cart is "
                    + "returned when the customer has never added items.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Cart contents")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    public CartResponse getCart() {
        return cartService.getCart();
    }

    @PostMapping("/items")
    @Operation(
            summary = "Add a product to the cart",
            description = "Adds quantity for an active product, or increases the quantity "
                    + "when the product is already in the cart. Stock must cover the resulting total.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Updated cart")
    @ApiResponse(responseCode = "400", description = "Validation failure or inactive product")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "Insufficient stock")
    public CartResponse addItem(@Valid @RequestBody AddCartItemCommand command) {
        return cartService.addItem(command);
    }

    @PatchMapping("/items/{productId}")
    @Operation(
            summary = "Update a cart line quantity",
            description = "Sets the quantity for an existing cart line. The product must still "
                    + "be active and stock must cover the new quantity.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Updated cart")
    @ApiResponse(responseCode = "400", description = "Validation failure or inactive product")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "404", description = "Product or cart line not found")
    @ApiResponse(responseCode = "409", description = "Insufficient stock")
    public CartResponse updateItem(
            @PathVariable Long productId,
            @Valid @RequestBody UpdateCartItemCommand command) {
        return cartService.updateItem(productId, command);
    }

    @DeleteMapping("/items/{productId}")
    @Operation(
            summary = "Remove a product from the cart",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Updated cart")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "404", description = "Product or cart line not found")
    public CartResponse removeItem(@PathVariable Long productId) {
        return cartService.removeItem(productId);
    }

    @DeleteMapping
    @Operation(
            summary = "Clear the cart",
            description = "Removes every line from the authenticated customer's cart.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Empty cart")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    public CartResponse clearCart() {
        return cartService.clearCart();
    }
}
