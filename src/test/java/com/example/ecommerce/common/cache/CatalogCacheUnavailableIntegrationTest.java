package com.example.ecommerce.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.category.service.CategoryService;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.product.dto.CreateProductCommand;
import com.example.ecommerce.product.dto.ProductResponse;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis is pointed at a closed local port for the whole test. Cache get/put
 * failures must fall through to PostgreSQL without breaking catalog reads.
 */
@SpringBootTest(
        properties = {
            "spring.cache.type=redis",
            "spring.data.redis.timeout=200ms",
            "spring.data.redis.connect-timeout=200ms"
        })
@ActiveProfiles("test")
@Testcontainers
class CatalogCacheUnavailableIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DATABASE_USERNAME", POSTGRES::getUsername);
        registry.add("DATABASE_PASSWORD", POSTGRES::getPassword);
        // No Redis process listens here; cache ops must fail open.
        registry.add("REDIS_URL", () -> "redis://127.0.0.1:1");
        registry.add("JWT_SECRET", () -> "test-only-jwt-secret-not-for-production");
        registry.add("CORS_ORIGINS", () -> "http://localhost");
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    void catalogApiWorksWhenRedisNeverBecomesAvailable() {
        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        productService.create(new CreateProductCommand(
                "KB-600",
                "Keyboard",
                "keyboard-offline",
                null,
                new BigDecimal("18.00"),
                CurrencyCode.EUR,
                2,
                books.getId()));

        ProductResponse product = productService.getResponse(
                productRepository.findAll().getFirst().getId(), false);
        assertThat(product.sku()).isEqualTo("KB-600");

        assertThat(categoryService.listActiveResponses())
                .extracting(response -> response.slug())
                .containsExactly("books");

        assertThat(productService.searchResponses(ProductSearchCriteria.of(
                        "books", null, null, "key", 0, 20, null)).totalElements())
                .isEqualTo(1);
    }
}
