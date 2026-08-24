package com.example.ecommerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.support.IntegrationTestContainers;
import com.example.ecommerce.inventory.service.InventoryConflictException;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.dto.UpdateOrderStatusCommand;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.order.repository.OrderItemRepository;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.order.service.AdminOrderService;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Administrative cancellation uses real inventory on the success path. Failure
 * injects only {@code restoreStock} so PostgreSQL rolls back the CANCELLED
 * status transition.
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
class AdminOrderCancellationTransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = IntegrationTestContainers.postgres();

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.registerPostgresWithoutRedis(registry, POSTGRES);
    }

    @SpyBean
    private InventoryService inventoryService;

    @Autowired
    private AdminOrderService adminOrderService;

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

    private Product keyboard;
    private Product mouse;
    private Long orderId;

    @BeforeEach
    void setUp() {
        Mockito.reset(inventoryService);
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        User customer = userRepository.saveAndFlush(
                User.registerCustomer("admin-txn@example.com", "test-only-password-hash", "Txn", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-ADM", "Keyboard", "keyboard-adm", null, new BigDecimal("49.50"), CurrencyCode.EUR, 8, books));
        mouse = productRepository.saveAndFlush(Product.create(
                "MS-ADM", "Mouse", "mouse-adm", null, new BigDecimal("10.00"), CurrencyCode.EUR, 5, books));

        Order order = Order.place("ORD-2026-008888", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(keyboard, 2);
        order.addItem(mouse, 1);
        orderId = orderRepository.saveAndFlush(order).getId();
    }

    @Test
    void rollsBackUncancelledOrderWhenInventoryRestoreFails() {
        doThrow(new InventoryConflictException(keyboard.getId()))
                .when(inventoryService)
                .restoreStock(eq(keyboard.getId()), eq(2));

        assertThatThrownBy(() -> adminOrderService.updateStatus(
                        orderId, new UpdateOrderStatusCommand(OrderStatus.CANCELLED)))
                .isInstanceOf(InventoryConflictException.class);

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);
        assertThat(productRepository.findById(mouse.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
    }

    @Test
    void rollsBackWhenSecondLineInventoryRestoreFailsAfterFirstLineSucceeded() {
        doThrow(new InventoryConflictException(mouse.getId()))
                .when(inventoryService)
                .restoreStock(eq(mouse.getId()), eq(1));

        assertThatThrownBy(() -> adminOrderService.updateStatus(
                        orderId, new UpdateOrderStatusCommand(OrderStatus.CANCELLED)))
                .isInstanceOf(InventoryConflictException.class);

        Order reloaded = orderRepository.findById(orderId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(8);
        assertThat(productRepository.findById(mouse.getId()).orElseThrow().getStockQuantity()).isEqualTo(5);
    }

    @Test
    void commitsAdminCancellationAndRestoresStockInPostgreSQL() {
        adminOrderService.updateStatus(orderId, new UpdateOrderStatusCommand(OrderStatus.CANCELLED));

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(productRepository.findById(keyboard.getId()).orElseThrow().getStockQuantity()).isEqualTo(10);
        assertThat(productRepository.findById(mouse.getId()).orElseThrow().getStockQuantity()).isEqualTo(6);
    }
}
