package com.example.ecommerce.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.repository.CartItemRepository;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.inventory.service.InsufficientStockException;
import com.example.ecommerce.inventory.service.InventoryConflictException;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.order.repository.OrderItemRepository;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.UserRole;
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
 * Concurrent catalog writes and inventory mutations against real PostgreSQL.
 * Optimistic locking must reject losers; stock must never go negative.
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
class ProductConcurrencyIntegrationTest {

    private static final int CONCURRENT_WRITERS = 8;

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
    private InventoryService inventoryService;

    private Category books;
    private User admin;
    private String adminBearer;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        admin = userRepository.saveAndFlush(User.create(
                "admin-product-conc@example.com",
                "test-only-password-hash",
                "Root",
                "Admin",
                UserRole.ADMIN));
        adminBearer = bearer(admin);
    }

    @Test
    void concurrentAdminPatchesWithTheSameVersionAcceptOnlyOneWrite() throws Exception {
        Product product = productRepository.saveAndFlush(Product.create(
                "KB-PATCH",
                "Keyboard",
                "keyboard-patch",
                null,
                new BigDecimal("49.50"),
                CurrencyCode.EUR,
                10,
                books));

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(CONCURRENT_WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENT_WRITERS; i++) {
                int priceCents = 50 + i;
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to start");
                        }
                        MvcResult result = mockMvc.perform(patch("/api/v1/products/" + product.getId())
                                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                                        .contentType(APPLICATION_JSON)
                                        .content(
                                                """
                                                {"version":0,"price":%d.00}
                                                """.formatted(priceCents)))
                                .andReturn();
                        int status = result.getResponse().getStatus();
                        if (status == 200) {
                            successes.incrementAndGet();
                        } else if (status == 409) {
                            conflicts.incrementAndGet();
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

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(CONCURRENT_WRITERS - 1);
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getStockQuantity()).isEqualTo(10);
        assertThat(reloaded.getPrice().intValue()).isBetween(50, 50 + CONCURRENT_WRITERS - 1);
    }

    @Test
    void concurrentAdminPutsWithTheSameVersionAcceptOnlyOneWrite() throws Exception {
        Product product = productRepository.saveAndFlush(Product.create(
                "KB-PUT",
                "Keyboard",
                "keyboard-put",
                null,
                new BigDecimal("49.50"),
                CurrencyCode.EUR,
                10,
                books));

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(CONCURRENT_WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_WRITERS);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENT_WRITERS; i++) {
                int priceCents = 60 + i;
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to start");
                        }
                        MvcResult result = mockMvc.perform(put("/api/v1/products/" + product.getId())
                                        .header(HttpHeaders.AUTHORIZATION, adminBearer)
                                        .contentType(APPLICATION_JSON)
                                        .content(
                                                """
                                                {
                                                  "version":0,
                                                  "name":"Keyboard",
                                                  "slug":"keyboard-put",
                                                  "description":null,
                                                  "price":%d.00,
                                                  "currency":"EUR",
                                                  "stockQuantity":10,
                                                  "categoryId":%d,
                                                  "active":true
                                                }
                                                """.formatted(priceCents, books.getId())))
                                .andReturn();
                        int status = result.getResponse().getStatus();
                        if (status == 200) {
                            successes.incrementAndGet();
                        } else if (status == 409) {
                            conflicts.incrementAndGet();
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

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(CONCURRENT_WRITERS - 1);
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getStockQuantity()).isEqualTo(10);
    }

    @Test
    void concurrentInventoryDecreasesNeverDriveStockNegative() throws Exception {
        final int initialStock = 3;
        final int attempts = 12;
        Product product = productRepository.saveAndFlush(Product.create(
                "KB-DEC",
                "Keyboard",
                "keyboard-dec",
                null,
                new BigDecimal("10.00"),
                CurrencyCode.EUR,
                initialStock,
                books));
        Long productId = product.getId();

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to start");
                        }
                        try {
                            inventoryService.decreaseStock(productId, 1);
                            successes.incrementAndGet();
                        } catch (InventoryConflictException | InsufficientStockException rejectedWrite) {
                            rejected.incrementAndGet();
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
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

        assertThat(successes.get()).isEqualTo(initialStock);
        assertThat(rejected.get()).isEqualTo(attempts - initialStock);
        assertThat(productRepository.findById(productId).orElseThrow().getStockQuantity()).isZero();
        assertThat(inventoryService.getStockLevel(productId).stockQuantity()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void checkoutRacingAdminPriceUpdateLeavesConsistentOrdersAndStock() throws Exception {
        Product product = productRepository.saveAndFlush(Product.create(
                "KB-RACE",
                "Keyboard",
                "keyboard-race",
                null,
                new BigDecimal("49.50"),
                CurrencyCode.EUR,
                1,
                books));
        User customer = userRepository.saveAndFlush(User.registerCustomer(
                "checkout-race@example.com", "test-only-password-hash", "Ada", "Customer"));
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(product, 1);
        cartRepository.saveAndFlush(cart);

        String customerBearer = bearer(customer);
        AtomicInteger checkoutCreated = new AtomicInteger();
        AtomicInteger checkoutRejected = new AtomicInteger();
        AtomicInteger patchOk = new AtomicInteger();
        AtomicInteger patchConflict = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();

        try {
            futures.add(pool.submit(() -> {
                try {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to start");
                    }
                    MvcResult result = mockMvc.perform(post("/api/v1/orders")
                                    .header(HttpHeaders.AUTHORIZATION, customerBearer)
                                    .contentType(APPLICATION_JSON)
                                    .content("{\"shippingAddress\":\"1 Main Street\"}"))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    if (status == 201) {
                        checkoutCreated.incrementAndGet();
                    } else if (status == 409 || status == 400) {
                        checkoutRejected.incrementAndGet();
                    } else {
                        throw new IllegalStateException("unexpected checkout status " + status);
                    }
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }));
            futures.add(pool.submit(() -> {
                try {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to start");
                    }
                    MvcResult result = mockMvc.perform(patch("/api/v1/products/" + product.getId())
                                    .header(HttpHeaders.AUTHORIZATION, adminBearer)
                                    .contentType(APPLICATION_JSON)
                                    .content("{\"version\":0,\"price\":99.00}"))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        patchOk.incrementAndGet();
                    } else if (status == 409) {
                        patchConflict.incrementAndGet();
                    } else {
                        throw new IllegalStateException("unexpected patch status " + status);
                    }
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(checkoutCreated.get() + checkoutRejected.get()).isEqualTo(1);
        assertThat(patchOk.get() + patchConflict.get()).isEqualTo(1);
        assertThat(orderRepository.count()).isEqualTo(checkoutCreated.get());
        int stock = productRepository.findById(product.getId()).orElseThrow().getStockQuantity();
        assertThat(stock).isGreaterThanOrEqualTo(0);
        assertThat(stock).isEqualTo(1 - (int) orderRepository.count());
        if (checkoutCreated.get() == 1) {
            var order = orderRepository.findWithItemsById(orderRepository.findAll().getFirst().getId())
                    .orElseThrow();
            BigDecimal unitPrice = order.getItems().getFirst().getUnitPrice();
            assertThat(unitPrice).satisfiesAnyOf(
                    price -> assertThat(price).isEqualByComparingTo("49.50"),
                    price -> assertThat(price).isEqualByComparingTo("99.00"));
            assertThat(order.getTotalAmount()).isEqualByComparingTo(order.getItems().getFirst().getLineTotal());
        }
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .accessToken();
    }
}
