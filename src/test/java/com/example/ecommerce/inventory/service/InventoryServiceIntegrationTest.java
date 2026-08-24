package com.example.ecommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.inventory.dto.StockLevel;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.service.ProductNotFoundException;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Inventory mutations commit in their own service transactions (no class-level
 * {@code @Transactional}) so concurrent optimistic-lock behaviour is observable.
 */
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
class InventoryServiceIntegrationTest {

    private static final int CONCURRENT_DECREASES = 8;

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
    private InventoryService inventoryService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private Category books;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
    }

    @Test
    void increaseDecreaseAndRestoreRoundTripAgainstPostgres() {
        Long productId = persistProduct("KB-100", "keyboard-100", 10).getId();

        StockLevel afterIncrease = inventoryService.increaseStock(productId, 5);
        assertThat(afterIncrease.stockQuantity()).isEqualTo(15);
        assertThat(afterIncrease.version()).isEqualTo(1L);

        StockLevel afterDecrease = inventoryService.decreaseStock(productId, 4);
        assertThat(afterDecrease.stockQuantity()).isEqualTo(11);
        assertThat(afterDecrease.version()).isEqualTo(2L);

        StockLevel afterRestore = inventoryService.restoreStock(productId, 4);
        assertThat(afterRestore.stockQuantity()).isEqualTo(15);
        assertThat(afterRestore.version()).isEqualTo(3L);

        assertThat(inventoryService.getStockLevel(productId).stockQuantity()).isEqualTo(15);
    }

    @Test
    void decreaseStockNeverGoesNegativeAndLeavesRowUnchangedOnFailure() {
        Long productId = persistProduct("KB-200", "keyboard-200", 3).getId();
        Long versionBefore = inventoryService.getStockLevel(productId).version();

        assertThatThrownBy(() -> inventoryService.decreaseStock(productId, 4))
                .isInstanceOf(InsufficientStockException.class);

        StockLevel level = inventoryService.getStockLevel(productId);
        assertThat(level.stockQuantity()).isEqualTo(3);
        assertThat(level.version()).isEqualTo(versionBefore);
    }

    @Test
    void validateAvailabilityDoesNotMutateStock() {
        Long productId = persistProduct("KB-300", "keyboard-300", 2).getId();
        Long versionBefore = inventoryService.getStockLevel(productId).version();

        inventoryService.validateAvailability(productId, 2);
        assertThatThrownBy(() -> inventoryService.validateAvailability(productId, 3))
                .isInstanceOf(InsufficientStockException.class);

        StockLevel level = inventoryService.getStockLevel(productId);
        assertThat(level.stockQuantity()).isEqualTo(2);
        assertThat(level.version()).isEqualTo(versionBefore);
    }

    @Test
    void unknownProductIsRejected() {
        assertThatThrownBy(() -> inventoryService.decreaseStock(999_999L, 1))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> inventoryService.validateAvailability(999_999L, 1))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        Long productId = persistProduct("KB-400", "keyboard-400", 5).getId();

        assertThatThrownBy(() -> inventoryService.increaseStock(productId, 0))
                .isInstanceOf(InvalidInventoryQuantityException.class);
        assertThatThrownBy(() -> inventoryService.decreaseStock(productId, -2))
                .isInstanceOf(InvalidInventoryQuantityException.class);
    }

    @Test
    void concurrentDecreasesPreserveNonNegativeStockViaOptimisticLocking() throws Exception {
        Long productId = persistProduct("KB-500", "keyboard-500", CONCURRENT_DECREASES).getId();

        CountDownLatch ready = new CountDownLatch(CONCURRENT_DECREASES);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_DECREASES);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENT_DECREASES; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to start");
                        }
                        try {
                            inventoryService.decreaseStock(productId, 1);
                            successes.incrementAndGet();
                        } catch (InventoryConflictException conflict) {
                            conflicts.incrementAndGet();
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
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        StockLevel finalLevel = inventoryService.getStockLevel(productId);
        assertThat(finalLevel.stockQuantity()).isGreaterThanOrEqualTo(0);
        assertThat(finalLevel.stockQuantity()).isEqualTo(CONCURRENT_DECREASES - successes.get());
        assertThat(successes.get() + conflicts.get()).isEqualTo(CONCURRENT_DECREASES);
        // Without optimistic locking, lost updates would leave stock too high.
        assertThat(successes.get()).isGreaterThanOrEqualTo(1);
        assertThat(finalLevel.stockQuantity() + successes.get()).isEqualTo(CONCURRENT_DECREASES);
    }

    private Product persistProduct(String sku, String slug, int stock) {
        return productRepository.saveAndFlush(Product.create(
                sku, "Keyboard", slug, null, new BigDecimal("49.50"), CurrencyCode.EUR, stock, books));
    }
}
