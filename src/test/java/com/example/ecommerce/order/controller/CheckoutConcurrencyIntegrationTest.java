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
import com.example.ecommerce.common.support.IntegrationTestContainers;
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

/**
 * Commits each request so concurrent checkout races exercise cart locks and
 * product optimistic locking. Invariants: never negative stock, never more
 * successful orders than available units, never two orders from one cart.
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
class CheckoutConcurrencyIntegrationTest {

    private static final int CONCURRENT_CHECKOUTS = 8;

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

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private CheckoutIdempotencyKeyRepository checkoutIdempotencyKeyRepository;

    private Category books;

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
        books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
    }

    @Test
    void manyBuyersOfLimitedStockNeverOversellOrLeavePartialOrders() throws Exception {
        final int stock = 3;
        final int buyers = 10;
        Product keyboard = persistProduct("KB-LIM", "keyboard-lim", stock);
        List<User> customers = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            User buyer = persistCustomer("buyer-lim-" + i + "@example.com");
            seedCart(buyer, keyboard, 1);
            customers.add(buyer);
        }

        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrent(buyers, customers, created, rejected);

        assertThat(created.get()).isEqualTo(stock);
        assertThat(rejected.get()).isEqualTo(buyers - stock);
        assertThat(orderRepository.count()).isEqualTo(stock);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isZero();

        long clearedCarts = customers.stream()
                .filter(user -> cartRepository.findWithItemsByUserId(user.getId())
                        .map(Cart::isEmpty)
                        .orElse(true))
                .count();
        long cartsStillHolding = customers.stream()
                .map(user -> cartRepository.findWithItemsByUserId(user.getId()))
                .filter(optional -> optional.isPresent() && !optional.get().isEmpty())
                .count();
        assertThat(clearedCarts).isEqualTo(stock);
        assertThat(cartsStillHolding).isEqualTo(buyers - stock);

        orderRepository.findAll().forEach(order -> {
            var withItems = orderRepository.findWithItemsById(order.getId()).orElseThrow();
            assertThat(withItems.getItems()).hasSize(1);
            assertThat(withItems.getTotalAmount())
                    .isEqualByComparingTo(withItems.getItems().getFirst().getLineTotal());
        });
    }

    @Test
    void twoCustomersCannotBothBuyTheLastUnit() throws Exception {
        Product keyboard = persistProduct("KB-LAST", "keyboard-last", 1);
        User alice = persistCustomer("alice-last@example.com");
        User bob = persistCustomer("bob-last@example.com");
        seedCart(alice, keyboard, 1);
        seedCart(bob, keyboard, 1);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrent(2, List.of(alice, bob), created, rejected);

        assertThat(created.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isZero();
    }

    @Test
    void concurrentCheckoutsOfTheSameCartCreateOnlyOneOrder() throws Exception {
        Product keyboard = persistProduct("KB-ONCE", "keyboard-once", 10);
        User alice = persistCustomer("alice-once@example.com");
        seedCart(alice, keyboard, 2);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<User> actors = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CHECKOUTS; i++) {
            actors.add(alice);
        }
        runConcurrent(CONCURRENT_CHECKOUTS, actors, created, rejected);

        assertThat(created.get()).isEqualTo(1);
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow().isEmpty()).isTrue();
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);
    }

    @Test
    void overlappingMultiItemCheckoutsNeverOversellOrLeavePartialOrders() throws Exception {
        Product keyboard = persistProduct("KB-OV", "keyboard-ov", 1);
        Product mouse = persistProduct("MS-OV", "mouse-ov", 1);
        User alice = persistCustomer("alice-ov@example.com");
        User bob = persistCustomer("bob-ov@example.com");

        Cart aliceCart = Cart.forUser(alice);
        aliceCart.addOrIncrease(keyboard, 1);
        aliceCart.addOrIncrease(mouse, 1);
        cartRepository.saveAndFlush(aliceCart);

        seedCart(bob, mouse, 1);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        runConcurrent(2, List.of(alice, bob), created, rejected);

        int keyboardStock = productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity();
        int mouseStock = productRepository.findById(mouse.getId()).orElseThrow().getStockQuantity();
        long orders = orderRepository.count();

        assertThat(created.get() + rejected.get()).isEqualTo(2);
        assertThat(created.get()).isEqualTo(1);
        assertThat(orders).isEqualTo(1);
        // Alice needs both SKUs; Bob needs only mouse. Exactly one buyer takes
        // the mouse; keyboard stock drops only when Alice is the winner.
        assertThat(mouseStock).isZero();
        long ordersTakingMouse = orderRepository.findAll().stream()
                .map(order -> orderRepository.findWithItemsById(order.getId()).orElseThrow())
                .filter(order -> order.getItems().stream().anyMatch(item -> item.getSku().equals("MS-OV")))
                .count();
        assertThat(ordersTakingMouse).isEqualTo(1);
        var winner = orderRepository.findWithItemsById(orderRepository.findAll().getFirst().getId()).orElseThrow();
        if (winner.getItems().size() == 2) {
            assertThat(keyboardStock).isZero();
            assertThat(winner.getUser().getId()).isEqualTo(alice.getId());
        } else {
            assertThat(winner.getItems()).hasSize(1);
            assertThat(keyboardStock).isEqualTo(1);
            assertThat(winner.getUser().getId()).isEqualTo(bob.getId());
        }
        orderRepository.findAll().forEach(order -> {
            var withItems = orderRepository.findWithItemsById(order.getId()).orElseThrow();
            assertThat(withItems.getItems()).isNotEmpty();
            assertThat(withItems.getTotalAmount())
                    .isEqualByComparingTo(withItems.getItems().stream()
                            .map(item -> item.getLineTotal())
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        });
    }

    private void runConcurrent(
            int threads,
            List<User> actors,
            AtomicInteger created,
            AtomicInteger rejected) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                User actor = actors.get(i);
                String bearer = bearer(actor);
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to start");
                        }
                        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                                        .header(HttpHeaders.AUTHORIZATION, bearer)
                                        .contentType(APPLICATION_JSON)
                                        .content("{\"shippingAddress\":\"1 Main Street\"}"))
                                .andReturn();
                        int status = result.getResponse().getStatus();
                        if (status == 201) {
                            created.incrementAndGet();
                        } else if (status == 409 || status == 400) {
                            rejected.incrementAndGet();
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
    }

    private Product persistProduct(String sku, String slug, int stock) {
        return productRepository.saveAndFlush(Product.create(
                sku, sku, slug, null, new BigDecimal("10.00"), CurrencyCode.EUR, stock, books));
    }

    private User persistCustomer(String email) {
        return userRepository.saveAndFlush(
                User.registerCustomer(email, "test-only-password-hash", "Cust", "Omer"));
    }

    private void seedCart(User owner, Product product, int quantity) {
        Cart cart = Cart.forUser(owner);
        cart.addOrIncrease(product, quantity);
        cartRepository.saveAndFlush(cart);
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .accessToken();
    }
}
