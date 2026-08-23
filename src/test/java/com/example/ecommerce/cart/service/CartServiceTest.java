package com.example.ecommerce.cart.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.cart.dto.AddCartItemCommand;
import com.example.ecommerce.cart.dto.CartResponse;
import com.example.ecommerce.cart.dto.UpdateCartItemCommand;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.mapper.CartMapper;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.security.CurrentUserProvider;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.service.ProductNotFoundException;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final long USER_ID = 11L;
    private static final long PRODUCT_ID = 42L;
    private static final Category CATEGORY = Category.create("Electronics", "electronics", null);

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private final CartMapper cartMapper = new CartMapper();

    private CartService cartService;

    @BeforeEach
    void setUp() {
        cartService = new CartService(
                cartRepository,
                productRepository,
                userRepository,
                currentUserProvider,
                cartMapper);
    }

    @Test
    void getCartReturnsEmptyCartWithoutPersistingWhenUserHasNoCart() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(cartRepository.findWithItemsByUserId(USER_ID)).thenReturn(Optional.empty());

        CartResponse response = cartService.getCart();

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isEqualByComparingTo("0.00");
        assertThat(response.totalQuantity()).isZero();
        assertThat(response.currency()).isEqualTo(CurrencyCode.EUR);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void getCartReturnsExistingCartForAuthenticatedUser() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Cart cart = cartWithProduct(activeProduct(10), 2);
        when(cartRepository.findWithItemsByUserId(USER_ID)).thenReturn(Optional.of(cart));

        CartResponse response = cartService.getCart();

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().quantity()).isEqualTo(2);
        assertThat(response.items().getFirst().lineTotal()).isEqualByComparingTo("99.00");
        assertThat(response.total()).isEqualByComparingTo("99.00");
        assertThat(response.totalQuantity()).isEqualTo(2);
    }

    @Test
    void addItemCreatesCartAndLineForAuthenticatedUser() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        User user = user(USER_ID);
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        Product product = activeProduct(10);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> {
            Cart saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 100L);
            return saved;
        });

        CartResponse response = cartService.addItem(new AddCartItemCommand(PRODUCT_ID, 2));

        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(cartCaptor.capture());
        Cart saved = cartCaptor.getValue();
        assertThat(saved.getUser().getId()).isEqualTo(USER_ID);
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().getFirst().getQuantity()).isEqualTo(2);
        assertThat(response.items()).hasSize(1);
        assertThat(response.total()).isEqualByComparingTo("99.00");
        assertThat(response.items().getFirst().lineTotal()).isEqualByComparingTo("99.00");
    }

    @Test
    void addItemIncreasesQuantityWhenProductAlreadyInCart() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Product product = activeProduct(10);
        Cart cart = cartWithProduct(product, 2);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(cartRepository.save(cart)).thenReturn(cart);

        CartResponse response = cartService.addItem(new AddCartItemCommand(PRODUCT_ID, 3));

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().getFirst().getQuantity()).isEqualTo(5);
        assertThat(response.totalQuantity()).isEqualTo(5);
        assertThat(response.total()).isEqualByComparingTo("247.50");
    }

    @Test
    void addItemRejectsInactiveProduct() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Product inactive = activeProduct(10);
        inactive.deactivate();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> cartService.addItem(new AddCartItemCommand(PRODUCT_ID, 1)))
                .isInstanceOf(InactiveProductForCartException.class)
                .hasMessageContaining(String.valueOf(PRODUCT_ID));
        verify(cartRepository, never()).save(any());
    }

    @Test
    void addItemRejectsMissingProduct() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cartService.addItem(new AddCartItemCommand(PRODUCT_ID, 1)))
                .isInstanceOf(ProductNotFoundException.class);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void addItemRejectsQuantityExceedingStock() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(Cart.forUser(user(USER_ID))));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(activeProduct(2)));

        assertThatThrownBy(() -> cartService.addItem(new AddCartItemCommand(PRODUCT_ID, 3)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("2");
        verify(cartRepository, never()).save(any());
    }

    @Test
    void addItemRejectsIncreaseThatWouldExceedStock() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Product product = activeProduct(5);
        Cart cart = cartWithProduct(product, 4);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> cartService.addItem(new AddCartItemCommand(PRODUCT_ID, 2)))
                .isInstanceOf(InsufficientStockException.class);
        verify(cartRepository, never()).save(any());
        assertThat(cart.getItems().getFirst().getQuantity()).isEqualTo(4);
    }

    @Test
    void addItemRejectsNonPositiveQuantity() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);

        assertThatThrownBy(() -> cartService.addItem(new AddCartItemCommand(PRODUCT_ID, 0)))
                .isInstanceOf(InvalidCartQuantityException.class);
        verify(productRepository, never()).findById(any());
    }

    @Test
    void updateItemChangesQuantityWithinStock() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Product product = activeProduct(10);
        Cart cart = cartWithProduct(product, 2);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(cartRepository.save(cart)).thenReturn(cart);

        CartResponse response = cartService.updateItem(PRODUCT_ID, new UpdateCartItemCommand(4));

        assertThat(cart.getItems().getFirst().getQuantity()).isEqualTo(4);
        assertThat(response.items().getFirst().lineTotal()).isEqualByComparingTo("198.00");
        assertThat(response.total()).isEqualByComparingTo("198.00");
    }

    @Test
    void updateItemRejectsQuantityAboveStock() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Product product = activeProduct(3);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> cartService.updateItem(PRODUCT_ID, new UpdateCartItemCommand(5)))
                .isInstanceOf(InsufficientStockException.class);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void updateItemRejectsInactiveProduct() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Product product = activeProduct(10);
        product.deactivate();
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> cartService.updateItem(PRODUCT_ID, new UpdateCartItemCommand(1)))
                .isInstanceOf(InactiveProductForCartException.class);
        verify(cartRepository, never()).save(any());
    }

    @Test
    void updateItemRejectsMissingCartLine() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(Cart.forUser(user(USER_ID))));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(activeProduct(10)));

        assertThatThrownBy(() -> cartService.updateItem(PRODUCT_ID, new UpdateCartItemCommand(1)))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void updateItemRejectsNonPositiveQuantity() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);

        assertThatThrownBy(() -> cartService.updateItem(PRODUCT_ID, new UpdateCartItemCommand(0)))
                .isInstanceOf(InvalidCartQuantityException.class);
    }

    @Test
    void removeItemDeletesLine() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Product product = activeProduct(10);
        Cart cart = cartWithProduct(product, 2);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(cartRepository.save(cart)).thenReturn(cart);

        CartResponse response = cartService.removeItem(PRODUCT_ID);

        assertThat(cart.isEmpty()).isTrue();
        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void removeItemRejectsMissingLine() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(Cart.forUser(user(USER_ID))));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(activeProduct(10)));

        assertThatThrownBy(() -> cartService.removeItem(PRODUCT_ID))
                .isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void clearCartRemovesAllLines() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Product keyboard = activeProduct(10);
        Product mouse = product(99L, "MS-001", "mouse", "10.00", 5, true);
        Cart cart = Cart.forUser(user(USER_ID));
        cart.addOrIncrease(keyboard, 2);
        cart.addOrIncrease(mouse, 1);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(cart));
        when(cartRepository.save(cart)).thenReturn(cart);

        CartResponse response = cartService.clearCart();

        assertThat(cart.isEmpty()).isTrue();
        assertThat(response.items()).isEmpty();
        assertThat(response.totalQuantity()).isZero();
        assertThat(response.total()).isEqualByComparingTo("0.00");
    }

    @Test
    void clearCartIsIdempotentWhenCartDoesNotExist() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        CartResponse response = cartService.clearCart();

        assertThat(response.items()).isEmpty();
        verify(cartRepository, never()).save(any());
    }

    @Test
    void ownershipAlwaysComesFromSecurityContextNotFromPayload() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        when(cartRepository.findWithItemsByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        when(userRepository.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(activeProduct(10)));
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cartService.addItem(new AddCartItemCommand(PRODUCT_ID, 1));

        verify(currentUserProvider).requireUserId();
        verify(cartRepository, atLeastOnce()).findWithItemsByUserIdForUpdate(USER_ID);
        verify(userRepository).findByIdForUpdate(USER_ID);
    }

    @Test
    void lineAndCartTotalsUseBigDecimalMoneyScale() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        Product product = product(PRODUCT_ID, "KB-001", "keyboard", "10.05", 10, true);
        Cart cart = cartWithProduct(product, 3);
        when(cartRepository.findWithItemsByUserId(USER_ID)).thenReturn(Optional.of(cart));

        CartResponse response = cartService.getCart();

        assertThat(response.items().getFirst().unitPrice()).isEqualByComparingTo("10.05");
        assertThat(response.items().getFirst().lineTotal()).isEqualByComparingTo("30.15");
        assertThat(response.total()).isEqualByComparingTo("30.15");
        assertThat(response.total().scale()).isEqualTo(2);
    }

    private Cart cartWithProduct(Product product, int quantity) {
        Cart cart = Cart.forUser(user(USER_ID));
        ReflectionTestUtils.setField(cart, "id", 7L);
        cart.addOrIncrease(product, quantity);
        return cart;
    }

    private static Product activeProduct(int stock) {
        return product(PRODUCT_ID, "KB-001", "keyboard", "49.50", stock, true);
    }

    private static Product product(
            long id, String sku, String slug, String price, int stock, boolean active) {
        Product created = Product.create(
                sku, "Keyboard", slug, null, new BigDecimal(price), null, stock, CATEGORY);
        ReflectionTestUtils.setField(created, "id", id);
        if (!active) {
            created.deactivate();
        }
        return created;
    }

    private static User user(long id) {
        User user = User.registerCustomer(
                "customer@example.com", "test-only-password-hash", "Ada", "Lovelace");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
