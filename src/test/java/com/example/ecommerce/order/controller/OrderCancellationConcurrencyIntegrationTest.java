package com.example.ecommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.repository.CartItemRepository;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.order.OrderStatus;
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
 * Commits each request (no class-level {@code @Transactional}) so concurrent
 * cancellation attempts exercise the order row lock and cannot restore stock
 * more than once.
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
class OrderCancellationConcurrencyIntegrationTest {

    private static final int CONCURRENT_CANCELS = 8;

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

    private User alice;
    private Product keyboard;
    private String aliceBearer;
    private Long orderId;

    @BeforeEach
    void setUp() throws Exception {
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        alice = userRepository.saveAndFlush(
                User.registerCustomer(
                        "alice-cancel-conc@example.com", "test-only-password-hash", "Alice", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-CNC",
                "Keyboard",
                "keyboard-cnc",
                null,
                new BigDecimal("49.50"),
                CurrencyCode.EUR,
                20,
                books));
        aliceBearer = bearer(alice);
        orderId = checkout(3);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(17);
    }

    @Test
    void concurrentCancellationRestoresInventoryOnlyOnce() throws Exception {
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(CONCURRENT_CANCELS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CANCELS);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENT_CANCELS; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to start");
                        }
                        MvcResult result = mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                                        .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                                .andReturn();
                        int status = result.getResponse().getStatus();
                        if (status == 200) {
                            successes.incrementAndGet();
                        } else if (status == 409) {
                            conflicts.incrementAndGet();
                        } else {
                            throw new IllegalStateException("unexpected status " + status);
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

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(CONCURRENT_CANCELS - 1);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(20);
    }

    private Long checkout(int quantity) throws Exception {
        Cart cart = Cart.forUser(alice);
        cart.addOrIncrease(keyboard, quantity);
        cartRepository.saveAndFlush(cart);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                        .contentType(APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"1 Main Street\"}"))
                .andExpect(status().isCreated());

        return orderRepository.findAll().getFirst().getId();
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .accessToken();
    }
}
