package com.example.ecommerce.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.category.dto.CreateCategoryCommand;
import com.example.ecommerce.category.dto.PatchCategoryCommand;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.category.service.CategoryService;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.inventory.service.InventoryService;
import com.example.ecommerce.product.dto.CreateProductCommand;
import com.example.ecommerce.product.dto.PatchProductCommand;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CatalogCacheIntegrationTest {

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
    }

    @Autowired
    private ProductService productService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CacheManager cacheManager;

    private Category books;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        clearCatalogCaches();
        books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
    }

    @Test
    void productRetrievalIsCachedAndInvalidatedOnWrite() {
        Product created = productService.create(new CreateProductCommand(
                "KB-100",
                "Keyboard",
                "keyboard",
                "mech",
                new BigDecimal("49.99"),
                CurrencyCode.EUR,
                5,
                books.getId()));

        ProductResponse first = productService.getResponse(created.getId(), false);
        assertThat(productCache().get(created.getId())).isNotNull();

        ProductResponse second = productService.getResponse(created.getId(), false);
        assertThat(second).isEqualTo(first);

        productService.patch(
                created.getId(),
                new PatchProductCommand(
                        created.getVersion(),
                        "Mechanical Keyboard",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertThat(productCache().get(created.getId())).isNull();
        assertThat(productService.getResponse(created.getId(), false).name())
                .isEqualTo("Mechanical Keyboard");
    }

    @Test
    void productSearchIsCachedAndClearedWhenCatalogChanges() {
        productService.create(new CreateProductCommand(
                "KB-200",
                "Keyboard",
                "keyboard-search",
                null,
                new BigDecimal("20.00"),
                CurrencyCode.EUR,
                3,
                books.getId()));

        ProductSearchCriteria criteria = ProductSearchCriteria.of(
                "books", null, null, "keyboard", 0, 10, null);
        String cacheKey = criteria.cacheKey();

        assertThat(productService.searchResponses(criteria).totalElements()).isEqualTo(1);
        assertThat(productsCache().get(cacheKey)).isNotNull();

        productService.create(new CreateProductCommand(
                "KB-201",
                "Keyboard Pro",
                "keyboard-pro",
                null,
                new BigDecimal("30.00"),
                CurrencyCode.EUR,
                2,
                books.getId()));

        assertThat(productsCache().get(cacheKey)).isNull();
        assertThat(productService.searchResponses(criteria).totalElements()).isEqualTo(2);
    }

    @Test
    void categoryRetrievalIsCachedAndCategoryWriteClearsProductCaches() {
        Product product = productService.create(new CreateProductCommand(
                "KB-300",
                "Keyboard",
                "keyboard-cat",
                null,
                new BigDecimal("15.00"),
                CurrencyCode.EUR,
                1,
                books.getId()));
        productService.getResponse(product.getId(), false);
        assertThat(productCache().get(product.getId())).isNotNull();

        assertThat(categoryService.getResponse(books.getId(), false).name()).isEqualTo("Books");
        assertThat(categoryCache().get(books.getId())).isNotNull();

        categoryService.listActiveResponses();
        assertThat(categoriesCache().get(CatalogCaches.ACTIVE_LIST_KEY)).isNotNull();

        categoryService.patch(
                books.getId(),
                new PatchCategoryCommand("Reading", null, null, null));

        assertThat(categoryCache().get(books.getId())).isNull();
        assertThat(categoriesCache().get(CatalogCaches.ACTIVE_LIST_KEY)).isNull();
        assertThat(productCache().get(product.getId())).isNull();
        assertThat(productService.getResponse(product.getId(), false).category().name())
                .isEqualTo("Reading");
    }

    @Test
    void createCategoryClearsActiveCategoryListCache() {
        categoryService.listActiveResponses();
        assertThat(categoriesCache().get(CatalogCaches.ACTIVE_LIST_KEY)).isNotNull();

        categoryService.create(new CreateCategoryCommand("Games", "games", null));

        assertThat(categoriesCache().get(CatalogCaches.ACTIVE_LIST_KEY)).isNull();
        assertThat(categoryService.listActiveResponses())
                .extracting(response -> response.slug())
                .contains("books", "games");
    }

    @Test
    void categoryDeactivationClearsCategoryListAndProductCaches() {
        Product product = productService.create(new CreateProductCommand(
                "KB-500",
                "Keyboard",
                "keyboard-deact",
                null,
                new BigDecimal("15.00"),
                CurrencyCode.EUR,
                1,
                books.getId()));
        productService.getResponse(product.getId(), false);
        categoryService.listActiveResponses();
        assertThat(productCache().get(product.getId())).isNotNull();
        assertThat(categoriesCache().get(CatalogCaches.ACTIVE_LIST_KEY)).isNotNull();

        categoryService.patch(books.getId(), new PatchCategoryCommand(null, null, null, false));

        assertThat(categoriesCache().get(CatalogCaches.ACTIVE_LIST_KEY)).isNull();
        assertThat(productCache().get(product.getId())).isNull();
        assertThat(categoryService.listActiveResponses()).isEmpty();
    }

    @Test
    void adminInactiveLookupBypassesPublicProductCache() {
        Product created = productService.create(new CreateProductCommand(
                "KB-400",
                "Hidden",
                "hidden-cache",
                null,
                new BigDecimal("9.99"),
                CurrencyCode.EUR,
                0,
                books.getId()));
        productService.deactivate(created.getId(), created.getVersion());

        assertThat(productCache().get(created.getId())).isNull();
        ProductResponse adminView = productService.getResponse(created.getId(), true);
        assertThat(adminView.active()).isFalse();
        assertThat(productCache().get(created.getId())).isNull();
    }

    @Test
    void inventoryStockChangeEvictsProductCaches() {
        Product created = productService.create(new CreateProductCommand(
                "KB-600",
                "Keyboard Stock",
                "keyboard-stock",
                null,
                new BigDecimal("25.00"),
                CurrencyCode.EUR,
                10,
                books.getId()));

        assertThat(productService.getResponse(created.getId(), false).stockQuantity()).isEqualTo(10);
        assertThat(productCache().get(created.getId())).isNotNull();

        inventoryService.decreaseStock(created.getId(), 3);

        assertThat(productCache().get(created.getId())).isNull();
        assertThat(productService.getResponse(created.getId(), false).stockQuantity()).isEqualTo(7);
    }

    private void clearCatalogCaches() {
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
    }

    private Cache productCache() {
        return cacheManager.getCache(CatalogCaches.PRODUCT);
    }

    private Cache categoryCache() {
        return cacheManager.getCache(CatalogCaches.CATEGORY);
    }

    private Cache productsCache() {
        return cacheManager.getCache(CatalogCaches.PRODUCTS);
    }

    private Cache categoriesCache() {
        return cacheManager.getCache(CatalogCaches.CATEGORIES);
    }
}
