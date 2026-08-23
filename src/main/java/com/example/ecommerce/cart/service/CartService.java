package com.example.ecommerce.cart.service;

import com.example.ecommerce.cart.dto.AddCartItemCommand;
import com.example.ecommerce.cart.dto.CartResponse;
import com.example.ecommerce.cart.dto.UpdateCartItemCommand;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.mapper.CartMapper;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.common.security.CurrentUserProvider;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.service.ProductNotFoundException;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cart use cases for the authenticated customer. Ownership always comes from
 * {@link CurrentUserProvider}; clients never supply a user id.
 *
 * <p>Cart lines are not reserved inventory. Stock checks here are advisory soft
 * guards against obvious overselling; checkout must re-validate and apply
 * optimistic locking on products. Concurrent cart mutations lock the cart row
 * so quantity merges are not lost under read-modify-write.
 */
@Service
@Transactional
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CartMapper cartMapper;

    public CartService(
            CartRepository cartRepository,
            ProductRepository productRepository,
            UserRepository userRepository,
            CurrentUserProvider currentUserProvider,
            CartMapper cartMapper) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.cartMapper = cartMapper;
    }

    @Transactional(readOnly = true)
    public CartResponse getCart() {
        long userId = currentUserProvider.requireUserId();
        return cartRepository.findWithItemsByUserId(userId)
                .map(cartMapper::toResponse)
                .orElseGet(CartService::emptyCartResponse);
    }

    public CartResponse addItem(AddCartItemCommand command) {
        long userId = currentUserProvider.requireUserId();
        requirePositiveQuantity(command.quantity());

        Product product = requireProduct(command.productId());
        requireActive(product);

        // Lock after product checks so failed adds do not create empty carts.
        // The lock serializes merges so concurrent adds cannot lose quantity.
        Cart cart = getOrCreateCartForUpdate(userId);
        int requestedTotal = cart.findItem(product)
                .map(item -> Math.addExact(item.getQuantity(), command.quantity()))
                .orElse(command.quantity());
        requireSufficientStock(product, requestedTotal);

        cart.addOrIncrease(product, command.quantity());
        return cartMapper.toResponse(cartRepository.save(cart));
    }

    public CartResponse updateItem(Long productId, UpdateCartItemCommand command) {
        long userId = currentUserProvider.requireUserId();
        requirePositiveQuantity(command.quantity());

        Product product = requireProduct(productId);
        requireActive(product);
        requireSufficientStock(product, command.quantity());

        Cart cart = requireCartForUpdate(userId, productId);
        if (cart.findItem(product).isEmpty()) {
            throw new CartItemNotFoundException(productId);
        }

        cart.changeQuantity(product, command.quantity());
        return cartMapper.toResponse(cartRepository.save(cart));
    }

    public CartResponse removeItem(Long productId) {
        long userId = currentUserProvider.requireUserId();
        Product product = requireProduct(productId);
        Cart cart = requireCartForUpdate(userId, productId);
        if (!cart.removeItem(product)) {
            throw new CartItemNotFoundException(productId);
        }
        return cartMapper.toResponse(cartRepository.save(cart));
    }

    public CartResponse clearCart() {
        long userId = currentUserProvider.requireUserId();
        return cartRepository.findWithItemsByUserIdForUpdate(userId)
                .map(cart -> {
                    cart.clear();
                    return cartMapper.toResponse(cartRepository.save(cart));
                })
                .orElseGet(CartService::emptyCartResponse);
    }

    /**
     * Locks an existing cart for update. If none exists yet, locks the user row
     * first so two concurrent first-adds cannot both insert and trip the unique
     * cart-per-user constraint (which would abort the PostgreSQL transaction).
     * The new cart is not flushed here; the caller persists it with its lines.
     */
    private Cart getOrCreateCartForUpdate(long userId) {
        return cartRepository.findWithItemsByUserIdForUpdate(userId).orElseGet(() -> {
            User owner = userRepository.findByIdForUpdate(userId)
                    .orElseThrow(() -> new CartOwnerNotFoundException(userId));
            return cartRepository.findWithItemsByUserIdForUpdate(userId)
                    .orElseGet(() -> Cart.forUser(owner));
        });
    }

    private Cart requireCartForUpdate(long userId, Long productId) {
        return cartRepository.findWithItemsByUserIdForUpdate(userId)
                .orElseThrow(() -> new CartItemNotFoundException(productId));
    }

    private Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    private static void requireActive(Product product) {
        if (!product.isActive()) {
            throw new InactiveProductForCartException(product.getId());
        }
    }

    private static void requireSufficientStock(Product product, int requestedQuantity) {
        int available = product.getStockQuantity();
        if (requestedQuantity > available) {
            throw new InsufficientStockException(product.getId(), available, requestedQuantity);
        }
    }

    private static void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new InvalidCartQuantityException("quantity must be greater than zero");
        }
    }

    private static CartResponse emptyCartResponse() {
        return new CartResponse(
                null,
                List.of(),
                PersistenceConventions.DEFAULT_CURRENCY,
                BigDecimal.ZERO.setScale(PersistenceConventions.MONEY_SCALE, RoundingMode.HALF_UP),
                0,
                null,
                null);
    }
}
