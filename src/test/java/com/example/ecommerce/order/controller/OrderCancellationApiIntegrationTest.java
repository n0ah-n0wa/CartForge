package com.example.ecommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.entity.Order;
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
class OrderCancellationApiIntegrationTest {

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
    private User bob;
    private Product keyboard;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        alice = userRepository.saveAndFlush(
                User.registerCustomer("alice@example.com", "test-only-password-hash", "Alice", "Customer"));
        bob = userRepository.saveAndFlush(
                User.registerCustomer("bob@example.com", "test-only-password-hash", "Bob", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-001", "Keyboard", "keyboard", null, new BigDecimal("49.50"), CurrencyCode.EUR, 10, books));
    }

    @Test
    void cancelPendingOrderRestoresStockAndPreservesSnapshots() throws Exception {
        Long orderId = checkout(alice, keyboard, 2);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].productName").value("Keyboard"))
                .andExpect(jsonPath("$.items[0].sku").value("KB-001"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(49.50))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.totalAmount").value(99.00));

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    @Test
    void cancelConfirmedOrderRestoresStock() throws Exception {
        Long orderId = checkout(alice, keyboard, 1);
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.transitionTo(OrderStatus.CONFIRMED);
        orderRepository.saveAndFlush(order);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    @Test
    void cancelRejectsShippedProcessingAndDeliveredOrders() throws Exception {
        assertNotCancellableAfterStatuses(OrderStatus.PROCESSING);
        assertNotCancellableAfterStatuses(OrderStatus.SHIPPED);
        assertNotCancellableAfterStatuses(OrderStatus.DELIVERED);
    }

    @Test
    void cancelRejectsAlreadyCancelledOrders() throws Exception {
        Long orderId = checkout(alice, keyboard, 1);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_TRANSITION"));

        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    @Test
    void cancelRequiresAuthenticationAndOwnership() throws Exception {
        Long orderId = checkout(alice, keyboard, 1);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")).andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(9);
    }

    @Test
    void snapshotsRemainAfterCancellationAndCatalogChanges() throws Exception {
        Long orderId = checkout(alice, keyboard, 1);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk());

        Product catalogKeyboard = productRepository.findById(keyboard.getId()).orElseThrow();
        catalogKeyboard.rename("Keyboard Pro", "keyboard-pro");
        catalogKeyboard.changePrice(new BigDecimal("99.99"), CurrencyCode.EUR);
        productRepository.saveAndFlush(catalogKeyboard);

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].productName").value("Keyboard"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(49.50));
    }

    private void assertNotCancellableAfterStatuses(OrderStatus targetStatus) throws Exception {
        Long orderId = checkout(alice, keyboard, 1);
        Order order = orderRepository.findById(orderId).orElseThrow();
        advanceTo(order, targetStatus);
        orderRepository.saveAndFlush(order);
        int stockBefore = productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity();

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_TRANSITION"));

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(targetStatus);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity())
                .isEqualTo(stockBefore);
    }

    private static void advanceTo(Order order, OrderStatus targetStatus) {
        if (order.getStatus() == targetStatus) {
            return;
        }
        switch (targetStatus) {
            case CONFIRMED -> order.transitionTo(OrderStatus.CONFIRMED);
            case PROCESSING -> {
                order.transitionTo(OrderStatus.CONFIRMED);
                order.transitionTo(OrderStatus.PROCESSING);
            }
            case SHIPPED -> {
                order.transitionTo(OrderStatus.CONFIRMED);
                order.transitionTo(OrderStatus.PROCESSING);
                order.transitionTo(OrderStatus.SHIPPED);
            }
            case DELIVERED -> {
                order.transitionTo(OrderStatus.CONFIRMED);
                order.transitionTo(OrderStatus.PROCESSING);
                order.transitionTo(OrderStatus.SHIPPED);
                order.transitionTo(OrderStatus.DELIVERED);
            }
            default -> throw new IllegalArgumentException("unsupported target status: " + targetStatus);
        }
    }

    private Long checkout(User owner, Product product, int quantity) throws Exception {
        Cart cart = cartRepository.findWithItemsByUserId(owner.getId()).orElseGet(() -> Cart.forUser(owner));
        cart.addOrIncrease(product, quantity);
        cartRepository.saveAndFlush(cart);

        mockMvc.perform(post("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"1 Main Street\"}"))
                .andExpect(status().isCreated());

        return orderRepository.findAll().stream()
                .filter(order -> order.getUser().getId().equals(owner.getId()))
                .reduce((first, second) -> second)
                .orElseThrow()
                .getId();
    }

    private String bearer(User user) {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .accessToken();
    }
}
