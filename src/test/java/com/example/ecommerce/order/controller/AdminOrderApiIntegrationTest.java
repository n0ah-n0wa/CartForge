package com.example.ecommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
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
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.repository.OrderItemRepository;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.UserRole;
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
class AdminOrderApiIntegrationTest {

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
    private User admin;
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
        admin = userRepository.saveAndFlush(User.create(
                "root@example.com", "test-only-password-hash", "Root", "Admin", UserRole.ADMIN));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-001", "Keyboard", "keyboard", null, new BigDecimal("49.50"), CurrencyCode.EUR, 10, books));
    }

    @Test
    void adminListsOrdersFromEveryCustomer() throws Exception {
        Long aliceOrderId = checkout(alice, 1);
        Long bobOrderId = checkout(bob, 1);

        mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].items").doesNotExist())
                .andExpect(jsonPath("$.content[*].id").value(org.hamcrest.Matchers.containsInAnyOrder(
                        aliceOrderId.intValue(), bobOrderId.intValue())));
    }

    @Test
    void adminCanFilterAndPaginate() throws Exception {
        checkout(alice, 1);
        Long bobOrderId = checkout(bob, 1);
        var bobOrder = orderRepository.findById(bobOrderId).orElseThrow();
        bobOrder.transitionTo(OrderStatus.CONFIRMED);
        orderRepository.saveAndFlush(bobOrder);

        mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .queryParam("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(bobOrderId.intValue()))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));

        mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .queryParam("page", "0")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void adminRetrievesAnyOrderWithHistoricalSnapshots() throws Exception {
        Long orderId = checkout(alice, 2);

        mockMvc.perform(get("/api/v1/admin/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.intValue()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.shippingAddress").value("1 Main Street"))
                .andExpect(jsonPath("$.items[0].productName").value("Keyboard"))
                .andExpect(jsonPath("$.items[0].sku").value("KB-001"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(49.50))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.totalAmount").value(99.00));
    }

    @Test
    void adminFollowsTheAllowedLifecycle() throws Exception {
        Long orderId = checkout(alice, 1);

        patchStatus(orderId, "CONFIRMED");
        patchStatus(orderId, "PROCESSING");
        patchStatus(orderId, "SHIPPED");
        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(9);
    }

    @Test
    void invalidTransitionsReturnConflictWithoutMutatingStock() throws Exception {
        Long orderId = checkout(alice, 1);
        int stockBefore = productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity();

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_TRANSITION"));

        patchStatus(orderId, "CONFIRMED");
        patchStatus(orderId, "PROCESSING");

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_TRANSITION"));

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_TRANSITION"));

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity())
                .isEqualTo(stockBefore);
    }

    @Test
    void adminCancellationRestoresInventoryAndKeepsSnapshots() throws Exception {
        Long orderId = checkout(alice, 2);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);

        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.items[0].productName").value("Keyboard"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(49.50))
                .andExpect(jsonPath("$.totalAmount").value(99.00));

        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
    }

    @Test
    void unknownOrdersAreNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders/999999").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/admin/orders/999999/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void customersCannotReachAdministrativeOrderEndpoints() throws Exception {
        Long orderId = checkout(alice, 1);

        mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isForbidden());

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void administrativeOrderEndpointsRequireAuthenticationAndAValidBody() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/orders/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/admin/orders/1/status")
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/admin/orders/1/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listRejectsUnsupportedSortFields() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .queryParam("sort", "shippingAddress,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
    }

    private void patchStatus(Long orderId, String status) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/orders/" + orderId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status));
    }

    private Long checkout(User owner, int quantity) throws Exception {
        Cart cart = cartRepository.findWithItemsByUserId(owner.getId()).orElseGet(() -> Cart.forUser(owner));
        cart.addOrIncrease(keyboard, quantity);
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
