package com.example.ecommerce.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.order.entity.CheckoutIdempotencyKey;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.cache.type=simple",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
@ActiveProfiles("test")
@Testcontainers
@Transactional
class CheckoutIdempotencyKeyRepositoryTest {

    private static final String FINGERPRINT = "a".repeat(64);

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
    private CheckoutIdempotencyKeyRepository checkoutIdempotencyKeyRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void rejectsASecondRowForTheSameUserAndKey() {
        User customer = userRepository.saveAndFlush(
                User.registerCustomer("idem@example.com", "test-only-password-hash", "Idem", "User"));
        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        Product keyboard = productRepository.saveAndFlush(Product.create(
                "KB-IDEM", "Keyboard", "keyboard-idem", null, new BigDecimal("10.00"), CurrencyCode.EUR, 5, books));

        Order first = Order.place("ORD-2026-001111", customer, "1 Main Street", CurrencyCode.EUR);
        first.addItem(keyboard, 1);
        first = orderRepository.saveAndFlush(first);

        Order second = Order.place("ORD-2026-001112", customer, "1 Main Street", CurrencyCode.EUR);
        second.addItem(keyboard, 1);
        second = orderRepository.saveAndFlush(second);

        checkoutIdempotencyKeyRepository.saveAndFlush(
                CheckoutIdempotencyKey.completed(customer, "checkout-1", FINGERPRINT, first));

        Order otherOrder = second;
        assertThatThrownBy(() -> checkoutIdempotencyKeyRepository.saveAndFlush(
                        CheckoutIdempotencyKey.completed(customer, "checkout-1", FINGERPRINT, otherOrder)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsTwoKeysPointingAtTheSameOrder() {
        User customer = userRepository.saveAndFlush(
                User.registerCustomer("idem2@example.com", "test-only-password-hash", "Idem", "User"));
        Category books = categoryRepository.saveAndFlush(Category.create("Media", "media", null));
        Product keyboard = productRepository.saveAndFlush(Product.create(
                "KB-IDEM2", "Keyboard", "keyboard-idem2", null, new BigDecimal("10.00"), CurrencyCode.EUR, 5, books));

        Order order = Order.place("ORD-2026-001113", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(keyboard, 1);
        order = orderRepository.saveAndFlush(order);

        checkoutIdempotencyKeyRepository.saveAndFlush(
                CheckoutIdempotencyKey.completed(customer, "key-a", FINGERPRINT, order));

        Order sameOrder = order;
        assertThatThrownBy(() -> checkoutIdempotencyKeyRepository.saveAndFlush(
                        CheckoutIdempotencyKey.completed(customer, "key-b", FINGERPRINT, sameOrder)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsACompletedKeyForTheUser() {
        User customer = userRepository.saveAndFlush(
                User.registerCustomer("idem3@example.com", "test-only-password-hash", "Idem", "User"));
        Category books = categoryRepository.saveAndFlush(Category.create("Toys", "toys", null));
        Product keyboard = productRepository.saveAndFlush(Product.create(
                "KB-IDEM3", "Keyboard", "keyboard-idem3", null, new BigDecimal("10.00"), CurrencyCode.EUR, 5, books));

        Order order = Order.place("ORD-2026-001114", customer, "1 Main Street", CurrencyCode.EUR);
        order.addItem(keyboard, 1);
        order = orderRepository.saveAndFlush(order);

        checkoutIdempotencyKeyRepository.saveAndFlush(
                CheckoutIdempotencyKey.completed(customer, "lookup-key", FINGERPRINT, order));

        assertThat(checkoutIdempotencyKeyRepository.findByUserIdAndIdempotencyKey(
                        customer.getId(), "lookup-key"))
                .isPresent()
                .get()
                .extracting(CheckoutIdempotencyKey::getOrder)
                .extracting(Order::getOrderNumber)
                .isEqualTo("ORD-2026-001114");
    }
}
