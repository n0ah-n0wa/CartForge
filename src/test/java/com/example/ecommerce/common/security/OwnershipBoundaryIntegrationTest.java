package com.example.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.support.IntegrationTestContainers;
import com.example.ecommerce.order.entity.Order;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Ownership and {@code @RequireAdmin} must bind to the security context, not
 * client-supplied identifiers. Cart/order cases hit real controllers; the probe
 * only covers {@code @RequireAdmin} method security (no dedicated admin-only
 * helper endpoint in production).
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
@Import(OwnershipBoundaryIntegrationTest.RequireAdminProbeController.class)
class OwnershipBoundaryIntegrationTest {

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
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    private User alice;
    private User bob;
    private Product keyboard;
    private Long aliceOrderId;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        alice = userRepository.saveAndFlush(
                User.registerCustomer("alice-own@example.com", "test-only-password-hash", "Alice", "Customer"));
        bob = userRepository.saveAndFlush(
                User.registerCustomer("bob-own@example.com", "test-only-password-hash", "Bob", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-OWN",
                "Keyboard",
                "keyboard-own",
                null,
                new BigDecimal("49.50"),
                CurrencyCode.EUR,
                10,
                books));

        Cart cart = Cart.forUser(alice);
        cart.addOrIncrease(keyboard, 2);
        cartRepository.saveAndFlush(cart);

        Order order = Order.place("ORD-2026-OWN001", alice, "1 Ownership Lane", CurrencyCode.EUR);
        order.addItem(keyboard, 1);
        aliceOrderId = orderRepository.saveAndFlush(order).getId();
    }

    @Test
    void cartIgnoresClientSuppliedUserIdAndReturnsTheTokenOwnersCart() throws Exception {
        mockMvc.perform(get("/api/v1/cart")
                        .param("userId", String.valueOf(bob.getId()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(keyboard.getId()))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void customerCannotAddToAnotherUsersCartViaProductIdConfusion() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob))
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":1}
                                """.formatted(keyboard.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        Cart aliceCart = cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow();
        assertThat(aliceCart.getItems()).hasSize(1);
        assertThat(aliceCart.getItems().iterator().next().getQuantity()).isEqualTo(2);

        Cart bobCart = cartRepository.findWithItemsByUserId(bob.getId()).orElseThrow();
        assertThat(bobCart.getItems()).hasSize(1);
    }

    @Test
    void customerReachesOwnOrderAndCannotReachAnotherCustomersOrder() throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + aliceOrderId).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceOrderId));

        mockMvc.perform(get("/api/v1/orders/" + aliceOrderId).header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void administratorCannotUseCustomerOrderPathAsOwnershipBypass() throws Exception {
        User admin = userRepository.saveAndFlush(
                User.registerCustomer("admin-own@example.com", "test-only-password-hash", "Admin", "User"));
        // Promote via role field used by JWT issuance; DB role for admin tokens is ADMIN.
        String adminToken = "Bearer "
                + jwtTokenService
                        .issue(new AuthenticatedUser(admin.getId(), admin.getEmail(), UserRole.ADMIN))
                        .accessToken();

        mockMvc.perform(get("/api/v1/orders/" + aliceOrderId).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void requireAdminRuleDeniesCustomersAndAllowsAdministrators() throws Exception {
        mockMvc.perform(get("/probe/admin-only").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/probe/admin-only")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer "
                                        + jwtTokenService
                                                .issue(new AuthenticatedUser(99L, "root@example.com", UserRole.ADMIN))
                                                .accessToken()))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void unauthenticatedRequestNeverReachesAnOwnershipCheck() throws Exception {
        mockMvc.perform(get("/api/v1/cart")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders/" + aliceOrderId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/probe/admin-only")).andExpect(status().isUnauthorized());
    }

    private String bearer(User user) {
        return "Bearer "
                + jwtTokenService
                        .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                        .accessToken();
    }

    @TestConfiguration
    @RestController
    @RequestMapping("/probe")
    static class RequireAdminProbeController {

        @GetMapping("/admin-only")
        @RequireAdmin
        String adminOnly() {
            return "ok";
        }
    }
}
