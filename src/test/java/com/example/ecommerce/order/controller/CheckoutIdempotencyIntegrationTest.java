package com.example.ecommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.repository.CartItemRepository;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.order.repository.CheckoutIdempotencyKeyRepository;
import com.example.ecommerce.order.repository.OrderItemRepository;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Idempotent checkout commits with PostgreSQL (no class-level
 * {@code @Transactional}) so failed keys do not persist a successful result.
 */
@SpringBootTest(
        properties = {
            "spring.cache.type=simple",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CheckoutIdempotencyIntegrationTest {

    private static final String KEY = "checkout-key-alice-1";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DATABASE_USERNAME", POSTGRES::getUsername);
        registry.add("DATABASE_PASSWORD", POSTGRES::getPassword);
        registry.add("REDIS_URL", () -> "redis://localhost:6379");
        registry.add("JWT_SECRET", () -> "test-only-jwt-secret-not-for-production");
        registry.add("CORS_ORIGINS", () -> "http://localhost");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private CheckoutIdempotencyKeyRepository checkoutIdempotencyKeyRepository;

    private User alice;
    private User bob;
    private Product keyboard;

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
        alice = userRepository.saveAndFlush(
                User.registerCustomer("alice-idem@example.com", "test-only-password-hash", "Alice", "Customer"));
        bob = userRepository.saveAndFlush(
                User.registerCustomer("bob-idem@example.com", "test-only-password-hash", "Bob", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-IDEM", "Keyboard", "keyboard-idem", null, new BigDecimal("10.00"), CurrencyCode.EUR, 20, books));
    }

    @Test
    void repeatedEquivalentCheckoutReturnsTheOriginalOrder() throws Exception {
        seedCart(alice, 2);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", KEY)
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers.startsWith("ORD-")));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", KEY)
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/orders/")))
                .andExpect(jsonPath("$.id").value(orderRepository.findAll().getFirst().getId().intValue()))
                .andExpect(jsonPath("$.items.length()").value(1));

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(checkoutIdempotencyKeyRepository.count()).isEqualTo(1);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(18);
        assertThat(cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow().isEmpty()).isTrue();
    }

    @Test
    void reusedKeyWithDifferentBodyIsRejected() throws Exception {
        seedCart(alice, 1);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", KEY)
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", KEY)
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("Other address")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void differentUsersMayShareTheSameKey() throws Exception {
        seedCart(alice, 1);
        seedCart(bob, 1);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", KEY)
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob))
                        .header("Idempotency-Key", KEY)
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isCreated());

        assertThat(orderRepository.count()).isEqualTo(2);
        assertThat(checkoutIdempotencyKeyRepository.count()).isEqualTo(2);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(18);
    }

    @Test
    void failedCheckoutDoesNotReserveTheKeyForALaterSuccess() throws Exception {
        seedCart(alice, 21);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", KEY)
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(orderRepository.count()).isZero();
        assertThat(checkoutIdempotencyKeyRepository.count()).isZero();

        Cart cart = cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow();
        cart.changeQuantity(keyboard, 1);
        cartRepository.saveAndFlush(cart);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", KEY)
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isCreated());

        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(checkoutIdempotencyKeyRepository.count()).isEqualTo(1);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(19);
    }

    @Test
    void invalidIdempotencyKeyIsRejected() throws Exception {
        seedCart(alice, 1);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .header("Idempotency-Key", "not a valid key")
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));

        assertThat(orderRepository.count()).isZero();
    }

    private void seedCart(User owner, int quantity) {
        Cart cart = Cart.forUser(owner);
        cart.addOrIncrease(keyboard, quantity);
        cartRepository.saveAndFlush(cart);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .accessToken();
    }

    private static String checkoutBody(String shippingAddress) {
        return """
                {"shippingAddress":"%s"}
                """.formatted(shippingAddress);
    }
}
