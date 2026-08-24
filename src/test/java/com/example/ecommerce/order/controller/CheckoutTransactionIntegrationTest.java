package com.example.ecommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.repository.CartItemRepository;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.security.JwtClaims;
import com.example.ecommerce.common.support.IntegrationTestContainers;
import com.example.ecommerce.inventory.service.InsufficientStockException;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.order.dto.CheckoutCommand;
import com.example.ecommerce.order.repository.CheckoutIdempotencyKeyRepository;
import com.example.ecommerce.order.repository.OrderItemRepository;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.service.OrderService;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Mid-checkout failure must roll back the whole unit against real PostgreSQL:
 * the first stock decrement is a real inventory write; only the second call is
 * forced to fail so the transaction boundary (not a mocked inventory service)
 * is what restores catalog stock and leaves the cart untouched.
 */
@SpringBootTest(
        properties = {
            "spring.cache.type=simple",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
@ActiveProfiles("test")
@Testcontainers
class CheckoutTransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = IntegrationTestContainers.postgres();

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.registerPostgresWithoutRedis(registry, POSTGRES);
    }

    /**
     * Spy only injects the second-line failure. Validation and the first
     * {@code decreaseStock} still hit PostgreSQL through the real service.
     */
    @SpyBean
    private InventoryService inventoryService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private CheckoutIdempotencyKeyRepository checkoutIdempotencyKeyRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private User customer;
    private Product keyboard;
    private Product mouse;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        checkoutIdempotencyKeyRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        customer = userRepository.saveAndFlush(
                User.registerCustomer("checkout-txn@example.com", "test-only-password-hash", "Txn", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-TXN2", "Keyboard", "keyboard-txn2", null, new BigDecimal("49.50"), CurrencyCode.EUR, 8, books));
        mouse = productRepository.saveAndFlush(Product.create(
                "MS-TXN2", "Mouse", "mouse-txn2", null, new BigDecimal("10.00"), CurrencyCode.EUR, 5, books));

        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(keyboard, 1);
        cart.addOrIncrease(mouse, 1);
        cartRepository.saveAndFlush(cart);
        authenticate(customer);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rollsBackCheckoutWhenTheSecondStockDecrementFails() {
        doThrow(new InsufficientStockException(mouse.getId(), 0, 1))
                .when(inventoryService)
                .decreaseStock(eq(mouse.getId()), eq(1));

        assertThatThrownBy(() -> orderService.checkout(new CheckoutCommand("1 Main Street")))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(orderRepository.count()).isZero();
        assertThat(checkoutIdempotencyKeyRepository.count()).isZero();
        Cart cart = cartRepository.findWithItemsByUserId(customer.getId()).orElseThrow();
        assertThat(cart.getItems()).hasSize(2);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);
        assertThat(productRepository.findById(mouse.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
    }

    private static void authenticate(User user) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(String.valueOf(user.getId()))
                .claim(JwtClaims.EMAIL, user.getEmail())
                .claim(JwtClaims.ROLE, user.getRole().name())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }
}
