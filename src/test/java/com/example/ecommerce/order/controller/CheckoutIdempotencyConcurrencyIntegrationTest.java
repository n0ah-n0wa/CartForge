package com.example.ecommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Concurrent retries with the same user and {@code Idempotency-Key} must create
 * one order. Commits are real (no class-level {@code @Transactional}).
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
class CheckoutIdempotencyConcurrencyIntegrationTest {

    private static final int CONCURRENT_RETRIES = 8;
    private static final String KEY = "concurrent-checkout-key";

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
    private Product keyboard;
    private String aliceBearer;

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
                User.registerCustomer(
                        "alice-idem-conc@example.com", "test-only-password-hash", "Alice", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-IDEMC",
                "Keyboard",
                "keyboard-idemc",
                null,
                new BigDecimal("10.00"),
                CurrencyCode.EUR,
                10,
                books));
        Cart cart = Cart.forUser(alice);
        cart.addOrIncrease(keyboard, 2);
        cartRepository.saveAndFlush(cart);
        aliceBearer = bearer(alice);
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateOnlyOneOrder() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(CONCURRENT_RETRIES);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_RETRIES);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENT_RETRIES; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to start");
                        }
                        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                                        .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                        .header("Idempotency-Key", KEY)
                                        .contentType(APPLICATION_JSON)
                                        .content("{\"shippingAddress\":\"1 Main Street\"}"))
                                .andReturn();
                        int status = result.getResponse().getStatus();
                        if (status == 201) {
                            created.incrementAndGet();
                        } else if (status == 200) {
                            replayed.incrementAndGet();
                        } else {
                            throw new IllegalStateException("unexpected status " + status + " "
                                    + result.getResponse().getContentAsString());
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(created.get()).isEqualTo(1);
        assertThat(replayed.get()).isEqualTo(CONCURRENT_RETRIES - 1);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(checkoutIdempotencyKeyRepository.count()).isEqualTo(1);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);
        assertThat(cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow().isEmpty()).isTrue();
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .accessToken();
    }
}
