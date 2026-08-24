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
import com.example.ecommerce.common.support.IntegrationTestContainers;
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

/**
 * Checkout commits in real transactions (no class-level {@code @Transactional})
 * so failure paths can assert that orders and stock changes did not persist.
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
class CheckoutApiIntegrationTest {

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

    private User alice;
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
        alice = userRepository.saveAndFlush(
                User.registerCustomer("alice@example.com", "test-only-password-hash", "Alice", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-001", "Keyboard", "keyboard", null, new BigDecimal("49.50"), CurrencyCode.EUR, 10, books));
        mouse = productRepository.saveAndFlush(Product.create(
                "MS-001", "Mouse", "mouse", null, new BigDecimal("19.99"), CurrencyCode.EUR, 5, books));
    }

    @Test
    void checkoutCreatesOrderDecrementsStockAndClearsCart() throws Exception {
        seedCart(alice, keyboard, 2, mouse, 1);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street, Springfield")))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/api/v1/orders/")))
                .andExpect(jsonPath("$.orderNumber").value(org.hamcrest.Matchers.startsWith("ORD-")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(118.99))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.shippingAddress").value("1 Main Street, Springfield"))
                .andExpect(jsonPath("$.items.length()").value(2));

        assertThat(orderRepository.count()).isEqualTo(1);
        Cart cleared = cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow();
        assertThat(cleared.isEmpty()).isTrue();
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);
        assertThat(productRepository.findById(mouse.getId()).orElseThrow().getStockQuantity()).isEqualTo(4);
    }

    @Test
    void checkoutRejectsEmptyCartWithoutPersistingAnOrder() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPTY_CART"));

        cartRepository.saveAndFlush(Cart.forUser(alice));

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMPTY_CART"));

        assertThat(orderRepository.count()).isZero();
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    @Test
    void checkoutRejectsInsufficientStockAndLeavesCartAndStockUnchanged() throws Exception {
        seedCart(alice, keyboard, 11);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(orderRepository.count()).isZero();
        Cart cart = cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow();
        assertThat(cart.getTotalQuantity()).isEqualTo(11);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    @Test
    void checkoutRejectsInactiveProductAndLeavesCartAndStockUnchanged() throws Exception {
        seedCart(alice, keyboard, 1);
        keyboard.deactivate();
        productRepository.saveAndFlush(keyboard);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INACTIVE_PRODUCT"));

        assertThat(orderRepository.count()).isZero();
        Cart cart = cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow();
        assertThat(cart.getTotalQuantity()).isEqualTo(1);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    @Test
    void checkoutRequiresAuthenticationAndValidShippingAddress() throws Exception {
        seedCart(alice, keyboard, 1);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(orderRepository.count()).isZero();
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    @Test
    void checkoutSnapshotsStayCorrectAfterCatalogChanges() throws Exception {
        seedCart(alice, keyboard, 1);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("Warehouse Bay 4")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].productName").value("Keyboard"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(49.50))
                .andExpect(jsonPath("$.totalAmount").value(49.50));

        // Checkout already mutated stock (and @Version); reload before catalog edits.
        Product catalogKeyboard = productRepository.findById(keyboard.getId()).orElseThrow();
        catalogKeyboard.rename("Keyboard Pro", "keyboard-pro");
        catalogKeyboard.changePrice(new BigDecimal("99.99"), CurrencyCode.EUR);
        productRepository.saveAndFlush(catalogKeyboard);

        String orderNumber = orderRepository.findAll().getFirst().getOrderNumber();
        var order = orderRepository.findWithItemsByOrderNumber(orderNumber).orElseThrow();
        assertThat(order.getItems().getFirst().getProductName()).isEqualTo("Keyboard");
        assertThat(order.getItems().getFirst().getUnitPrice()).isEqualByComparingTo("49.50");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("49.50");
    }

    @Test
    void checkoutRejectsWhenAnyLineIsOutOfStockAndLeavesEarlierLinesUnchanged() throws Exception {
        mouse.decreaseStock(5);
        productRepository.saveAndFlush(mouse);
        assertThat(mouse.getStockQuantity()).isZero();
        seedCart(alice, keyboard, 2, mouse, 1);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(checkoutBody("1 Main Street")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertThat(orderRepository.count()).isZero();
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
        assertThat(productRepository.findById(mouse.getId()).orElseThrow().getStockQuantity()).isZero();
        Cart cart = cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow();
        assertThat(cart.getTotalQuantity()).isEqualTo(3);
    }

    private void seedCart(User owner, Product first, int firstQty) {
        Cart cart = Cart.forUser(owner);
        cart.addOrIncrease(first, firstQty);
        cartRepository.saveAndFlush(cart);
    }

    private void seedCart(User owner, Product first, int firstQty, Product second, int secondQty) {
        Cart cart = Cart.forUser(owner);
        cart.addOrIncrease(first, firstQty);
        cart.addOrIncrease(second, secondQty);
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
