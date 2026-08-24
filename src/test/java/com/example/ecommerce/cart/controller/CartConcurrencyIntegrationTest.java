package com.example.ecommerce.cart.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.example.ecommerce.common.support.IntegrationTestContainers;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Commits each request (no class-level {@code @Transactional}) so concurrent cart
 * mutations exercise real row locks and the unique cart-per-user constraint.
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
class CartConcurrencyIntegrationTest {

    private static final int CONCURRENT_ADDS = 8;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = IntegrationTestContainers.postgres();

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.registerPostgresWithoutRedis(registry, POSTGRES);
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

    private User alice;
    private Product keyboard;
    private Product mouse;
    private String aliceBearer;

    @BeforeEach
    void setUp() {
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        alice = userRepository.saveAndFlush(
                User.registerCustomer(
                        "alice-concurrent@example.com", "test-only-password-hash", "Alice", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-CONC",
                "Keyboard",
                "keyboard-conc",
                null,
                new BigDecimal("49.50"),
                CurrencyCode.EUR,
                100,
                books));
        mouse = productRepository.saveAndFlush(Product.create(
                "MS-CONC",
                "Mouse",
                "mouse-conc",
                null,
                new BigDecimal("10.00"),
                CurrencyCode.EUR,
                100,
                books));
        aliceBearer = bearer(alice);
    }

    @Test
    void concurrentAddsToTheSameProductAccumulateQuantityWithoutLosingUpdates() throws Exception {
        runConcurrentAdds(keyboard.getId(), CONCURRENT_ADDS);

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(CONCURRENT_ADDS))
                .andExpect(jsonPath("$.totalQuantity").value(CONCURRENT_ADDS));

        assertThat(cartRepository.findByUserId(alice.getId())).isPresent();
        assertThat(cartRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentFirstAddsCreateOnlyOneCartPerCustomer() throws Exception {
        runConcurrentAdds(keyboard.getId(), CONCURRENT_ADDS);

        assertThat(cartRepository.findAll()).hasSize(1);
        assertThat(cartRepository.findByUserId(alice.getId())).isPresent();
        assertThat(cartItemRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentQuantityUpdatesSerializeOnTheCartRow() throws Exception {
        Cart cart = Cart.forUser(alice);
        cart.addOrIncrease(keyboard, 1);
        cartRepository.saveAndFlush(cart);

        int threads = 6;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int quantity = 2; quantity <= threads + 1; quantity++) {
                int target = quantity;
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to start");
                        }
                        mockMvc.perform(patch("/api/v1/cart/items/{productId}", keyboard.getId())
                                        .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                        .contentType(APPLICATION_JSON)
                                        .content("""
                                                {"quantity":%d}
                                                """.formatted(target)))
                                .andExpect(status().isOk());
                        successes.incrementAndGet();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            assertThat(successes.get()).isEqualTo(threads);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, aliceBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        int finalQuantity = cartRepository
                .findWithItemsByUserId(alice.getId())
                .orElseThrow()
                .getItems()
                .iterator()
                .next()
                .getQuantity();
        assertThat(finalQuantity).isBetween(2, threads + 1);
        assertThat(cartRepository.findAll()).hasSize(1);
    }

    @Test
    void concurrentAddAndRemoveLeaveAConsistentCart() throws Exception {
        Cart cart = Cart.forUser(alice);
        cart.addOrIncrease(keyboard, 1);
        cartRepository.saveAndFlush(cart);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to start");
                }
                return mockMvc.perform(post("/api/v1/cart/items")
                                .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                .contentType(APPLICATION_JSON)
                                .content(addBody(mouse.getId(), 1)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to start");
                }
                return mockMvc.perform(delete("/api/v1/cart/items/{productId}", keyboard.getId())
                                .header(HttpHeaders.AUTHORIZATION, aliceBearer))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int addStatus = futures.get(0).get(30, TimeUnit.SECONDS);
            int removeStatus = futures.get(1).get(30, TimeUnit.SECONDS);
            assertThat(addStatus).isEqualTo(200);
            assertThat(removeStatus).isEqualTo(200);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        Cart reloaded = cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow();
        assertThat(reloaded.getItems()).hasSize(1);
        assertThat(reloaded.getItems().iterator().next().getProduct().getId()).isEqualTo(mouse.getId());
        assertThat(cartRepository.findAll()).hasSize(1);
    }

    private void runConcurrentAdds(Long productId, int threads) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to start");
                        }
                        mockMvc.perform(post("/api/v1/cart/items")
                                        .header(HttpHeaders.AUTHORIZATION, aliceBearer)
                                        .contentType(APPLICATION_JSON)
                                        .content(addBody(productId, 1)))
                                .andExpect(status().isOk());
                        successes.incrementAndGet();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            assertThat(successes.get()).isEqualTo(threads);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .accessToken();
    }

    private static String addBody(Long productId, int quantity) {
        return """
                {"productId":%d,"quantity":%d}
                """.formatted(productId, quantity);
    }
}
