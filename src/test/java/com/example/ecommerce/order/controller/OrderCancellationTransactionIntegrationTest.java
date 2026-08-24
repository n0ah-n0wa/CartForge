package com.example.ecommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.security.JwtClaims;
import com.example.ecommerce.inventory.dto.StockLevel;
import com.example.ecommerce.inventory.service.InventoryConflictException;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.order.repository.OrderItemRepository;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.service.OrderService;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies cancellation rolls back when inventory restoration fails after the
 * status transition, leaving the order uncancelled in the database.
 */
@SpringBootTest(
        properties = {
            "spring.cache.type=simple",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
@ActiveProfiles("test")
@Testcontainers
class OrderCancellationTransactionIntegrationTest {

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

    @MockBean
    private InventoryService inventoryService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private User customer;
    private Product keyboard;
    private Long orderId;

    @BeforeEach
    void setUp() {
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        customer = userRepository.saveAndFlush(
                User.registerCustomer("txn@example.com", "test-only-password-hash", "Txn", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-TXN", "Keyboard", "keyboard-txn", null, new BigDecimal("49.50"), CurrencyCode.EUR, 8, books));

        Order order = Order.place("ORD-2026-009999", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(keyboard, 2);
        orderId = orderRepository.saveAndFlush(order).getId();
        authenticate(customer);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rollsBackUncancelledOrderWhenInventoryRestoreFails() {
        when(inventoryService.restoreStock(eq(keyboard.getId()), eq(2)))
                .thenThrow(new InventoryConflictException(keyboard.getId()));

        assertThatThrownBy(() -> orderService.cancelOrder(orderId))
                .isInstanceOf(InventoryConflictException.class);

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);
    }

    @Test
    void commitsCancellationWhenInventoryRestoreSucceeds() {
        when(inventoryService.restoreStock(eq(keyboard.getId()), eq(2)))
                .thenReturn(new StockLevel(keyboard.getId(), 10, 1L));

        orderService.cancelOrder(orderId);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private static void authenticate(User user) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(String.valueOf(user.getId()))
                .claim(JwtClaims.EMAIL, user.getEmail())
                .claim(JwtClaims.ROLE, user.getRole().name())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }
}
