package com.example.ecommerce.cart.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.example.ecommerce.cart.dto.AddCartItemCommand;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.cart.service.CartService;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.security.JwtClaims;
import com.example.ecommerce.common.support.IntegrationTestContainers;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
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
 * Cart mutations run in one transaction: a failure on persist must not leave an
 * empty cart or a half-written line in PostgreSQL.
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
class CartTransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = IntegrationTestContainers.postgres();

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.registerPostgresWithoutRedis(registry, POSTGRES);
    }

    @SpyBean
    private CartRepository cartRepository;

    @Autowired
    private CartService cartService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private User customer;
    private Product keyboard;

    @BeforeEach
    void setUp() {
        Mockito.reset(cartRepository);
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        customer = userRepository.saveAndFlush(
                User.registerCustomer("cart-txn@example.com", "test-only-password-hash", "Cart", "Txn"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-CART-TXN",
                "Keyboard",
                "keyboard-cart-txn",
                null,
                new BigDecimal("49.50"),
                CurrencyCode.EUR,
                10,
                books));
        authenticate(customer);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rollsBackFirstAddWhenCartPersistFails() {
        doThrow(new DataAccessResourceFailureException("simulated cart write failure"))
                .when(cartRepository)
                .save(any(Cart.class));

        assertThatThrownBy(() -> cartService.addItem(new AddCartItemCommand(keyboard.getId(), 1)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(cartRepository.findByUserId(customer.getId())).isEmpty();
        assertThat(cartRepository.findAll()).isEmpty();
    }

    @Test
    void rollsBackQuantityChangeWhenCartPersistFails() {
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(keyboard, 1);
        cartRepository.saveAndFlush(cart);
        Mockito.reset(cartRepository);

        doThrow(new DataAccessResourceFailureException("simulated cart write failure"))
                .when(cartRepository)
                .save(any(Cart.class));

        assertThatThrownBy(() -> cartService.addItem(new AddCartItemCommand(keyboard.getId(), 2)))
                .isInstanceOf(DataAccessResourceFailureException.class);

        Cart reloaded = cartRepository.findWithItemsByUserId(customer.getId()).orElseThrow();
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getItems().iterator().next().getQuantity()).isEqualTo(1);
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
