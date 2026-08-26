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
import com.example.ecommerce.cart.repository.CartItemRepository;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.support.IntegrationTestContainers;
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

/**
 * Cart HTTP contracts against real PostgreSQL commits (no class-level
 * {@code @Transactional}).
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
class CartApiIntegrationTest {

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

    private Category books;
    private User alice;
    private User bob;
    private Product keyboard;
    private Product mouse;

    @BeforeEach
    void setUp() {
        cartItemRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        alice = userRepository.saveAndFlush(
                User.registerCustomer("alice@example.com", "test-only-password-hash", "Alice", "Customer"));
        bob = userRepository.saveAndFlush(
                User.registerCustomer("bob@example.com", "test-only-password-hash", "Bob", "Customer"));
        keyboard = productRepository.saveAndFlush(product("KB-001", "Keyboard", "keyboard", "49.50", 10));
        mouse = productRepository.saveAndFlush(product("MS-001", "Mouse", "mouse", "19.99", 5));
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/cart")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(APPLICATION_JSON)
                        .content(addBody(keyboard.getId(), 1)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/cart/items/{productId}", keyboard.getId())
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(1)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/cart/items/{productId}", keyboard.getId()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/cart")).andExpect(status().isUnauthorized());
    }

    @Test
    void emptyCartIsReturnedWithoutPersisting() throws Exception {
        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((Object) null))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0.00))
                .andExpect(jsonPath("$.totalQuantity").value(0))
                .andExpect(jsonPath("$.currency").value("EUR"));

        assertThat(cartRepository.findByUserId(alice.getId())).isEmpty();
    }

    @Test
    void addUpdateRemoveAndClearRoundTrip() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(keyboard.getId(), 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(keyboard.getId()))
                .andExpect(jsonPath("$.items[0].sku").value("KB-001"))
                .andExpect(jsonPath("$.items[0].name").value("Keyboard"))
                .andExpect(jsonPath("$.items[0].slug").value("keyboard"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(49.50))
                .andExpect(jsonPath("$.items[0].lineTotal").value(99.00))
                .andExpect(jsonPath("$.total").value(99.00))
                .andExpect(jsonPath("$.totalQuantity").value(2));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(keyboard.getId(), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(3))
                .andExpect(jsonPath("$.items[0].lineTotal").value(148.50))
                .andExpect(jsonPath("$.total").value(148.50))
                .andExpect(jsonPath("$.totalQuantity").value(3));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(mouse.getId(), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalQuantity").value(4));

        mockMvc.perform(patch("/api/v1/cart/items/{productId}", mouse.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.productId==" + mouse.getId() + ")].quantity").value(2))
                .andExpect(jsonPath("$.totalQuantity").value(5));

        mockMvc.perform(delete("/api/v1/cart/items/{productId}", keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(mouse.getId()))
                .andExpect(jsonPath("$.totalQuantity").value(2));

        mockMvc.perform(delete("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0.00))
                .andExpect(jsonPath("$.totalQuantity").value(0));

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(0));
    }

    @Test
    void customersOnlySeeAndMutateTheirOwnCart() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(keyboard.getId(), 2)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(0));

        mockMvc.perform(patch("/api/v1/cart/items/{productId}", keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/cart/items/{productId}", keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(mouse.getId(), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(mouse.getId()));

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(keyboard.getId()))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        mockMvc.perform(delete("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void rejectsMissingProductInactiveProductAndInsufficientStock() throws Exception {
        Product inactive = product("IN-001", "Hidden", "hidden", "9.99", 3);
        inactive.deactivate();
        inactive = productRepository.saveAndFlush(inactive);

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(999_999L, 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(inactive.getId(), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INACTIVE_PRODUCT"));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(mouse.getId(), 6)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(mouse.getId(), 4)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(mouse.getId(), 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        mockMvc.perform(patch("/api/v1/cart/items/{productId}", mouse.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(6)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void rejectsValidationFailuresAndMissingCartLines() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(keyboard.getId(), 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(keyboard.getId(), -1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/v1/cart/items/{productId}", keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/v1/cart/items/{productId}", keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/cart/items/{productId}", keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(keyboard.getId(), 1)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/cart/items/{productId}", mouse.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(APPLICATION_JSON)
                        .content(updateBody(1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));
    }

    @Test
    void unknownOwnerCannotCreateACart() throws Exception {
        String orphanToken = "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(999_999L, "ghost@example.com", UserRole.CUSTOMER))
                .accessToken();

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, orphanToken)
                        .contentType(APPLICATION_JSON)
                        .content(addBody(keyboard.getId(), 1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void administratorsUseTheirOwnCartLikeAnyAuthenticatedUser() throws Exception {
        User admin = userRepository.saveAndFlush(
                User.create("admin@example.com", "test-only-password-hash", "Root", "Admin", UserRole.ADMIN));

        mockMvc.perform(post("/api/v1/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(APPLICATION_JSON)
                        .content(addBody(keyboard.getId(), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(keyboard.getId()));

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private Product product(String sku, String name, String slug, String price, int stock) {
        return Product.create(sku, name, slug, null, new BigDecimal(price), CurrencyCode.EUR, stock, books);
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

    private static String updateBody(int quantity) {
        return """
                {"quantity":%d}
                """.formatted(quantity);
    }
}
