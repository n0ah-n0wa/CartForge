package com.example.ecommerce.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.product.dto.CreateProductCommand;
import com.example.ecommerce.product.dto.ProductResponse;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.service.ProductSearchCriteria;
import com.example.ecommerce.product.service.ProductService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies catalog reads remain correct when Redis becomes unreachable after
 * the application has already started (graceful degradation to PostgreSQL).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CatalogCacheDegradationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DATABASE_USERNAME", POSTGRES::getUsername);
        registry.add("DATABASE_PASSWORD", POSTGRES::getPassword);
        registry.add(
                "REDIS_URL",
                () -> "redis://%s:%d".formatted(REDIS.getHost(), REDIS.getMappedPort(6379)));
        registry.add("JWT_SECRET", () -> "test-only-jwt-secret-not-for-production");
        registry.add("CORS_ORIGINS", () -> "http://localhost");
        registry.add("spring.cache.type", () -> "redis");
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("spring.data.redis.connect-timeout", () -> "200ms");
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private Product product;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        product = productService.create(new CreateProductCommand(
                "KB-500",
                "Keyboard",
                "keyboard-degrade",
                null,
                new BigDecimal("12.50"),
                CurrencyCode.EUR,
                4,
                books.getId()));
        // Warm the cache while Redis is healthy.
        productService.getResponse(product.getId(), false);
        productService.searchResponses(ProductSearchCriteria.of(
                null, null, null, null, 0, 10, null));
    }

    @Test
    void catalogReadsContinueFromPostgreSQLWhenRedisStops() {
        REDIS.stop();

        ProductResponse byId = productService.getResponse(product.getId(), false);
        assertThat(byId.sku()).isEqualTo("KB-500");
        assertThat(byId.name()).isEqualTo("Keyboard");

        assertThat(productService.searchResponses(ProductSearchCriteria.of(
                        null, null, null, "keyboard", 0, 10, null)).content())
                .extracting(ProductResponse::sku)
                .containsExactly("KB-500");
    }
}
