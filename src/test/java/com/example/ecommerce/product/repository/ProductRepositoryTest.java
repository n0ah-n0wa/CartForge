package com.example.ecommerce.product.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.category.service.CategoryInUseException;
import com.example.ecommerce.category.service.CategoryService;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.service.ProductCategoryReference;
import com.example.ecommerce.product.service.ProductSearchCriteria;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
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
class ProductRepositoryTest {

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
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesIdentityTimestampsVersionAndCatalogDefaults() {
        Instant beforePersist = Instant.now().minusSeconds(1);
        Category category = persistedCategory("Books", "books");

        Product saved = productRepository.saveAndFlush(product("KB-001", "keyboard", category));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isAfter(beforePersist);
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
        assertThat(saved.getPrice()).isEqualByComparingTo("49.50");
        assertThat(saved.getCurrency()).isEqualTo(CurrencyCode.EUR);
        assertThat(saved.isActive()).isTrue();
        assertThat(productRepository.findBySku("KB-001")).isPresent();
        assertThat(productRepository.existsBySlug("keyboard")).isTrue();
    }

    @Test
    void storesPriceAsExactDecimalNotFloatingPoint() {
        Category category = persistedCategory("Books", "books");
        productRepository.saveAndFlush(Product.create(
                "KB-002", "Keyboard", "keyboard", null, new BigDecimal("0.10"), null, 1, category));
        entityManager.clear();

        BigDecimal stored = jdbcTemplate.queryForObject(
                "select price from products where sku = 'KB-002'", BigDecimal.class);

        assertThat(stored).isEqualByComparingTo("0.10");
        assertThat(stored.scale()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        """
                        select data_type from information_schema.columns
                        where table_name = 'products' and column_name = 'price'
                        """,
                        String.class))
                .isEqualTo("numeric");
    }

    @Test
    void rejectsDuplicateSku() {
        Category category = persistedCategory("Books", "books");
        productRepository.saveAndFlush(product("KB-001", "keyboard", category));

        assertThatThrownBy(() -> productRepository.saveAndFlush(product("KB-001", "mouse", category)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateSlug() {
        Category category = persistedCategory("Books", "books");
        productRepository.saveAndFlush(product("KB-001", "keyboard", category));

        assertThatThrownBy(() -> productRepository.saveAndFlush(product("KB-002", "keyboard", category)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNegativePrice() {
        Long categoryId = persistedCategory("Books", "books").getId();

        assertThatThrownBy(() -> insertProduct("KB-900", "negative-price", "-0.01", 1, categoryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_products_price_non_negative");
    }

    @Test
    void databaseRejectsNegativeStock() {
        Long categoryId = persistedCategory("Books", "books").getId();

        assertThatThrownBy(() -> insertProduct("KB-901", "negative-stock", "1.00", -1, categoryId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_products_stock_quantity_non_negative");
    }

    @Test
    void databaseRejectsUnknownCategory() {
        assertThatThrownBy(() -> insertProduct("KB-902", "orphan", "1.00", 1, 9_999L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_products_categories");
    }

    @Test
    void databaseRestrictsCategoryDeletionWhileProductsExist() {
        Category category = persistedCategory("Books", "books");
        productRepository.saveAndFlush(product("KB-001", "keyboard", category));

        assertThatThrownBy(() -> jdbcTemplate.update("delete from categories where id = ?", category.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_products_categories");
    }

    @Test
    void categoryServiceRejectsDeleteWhenProductsExist() {
        Category category = persistedCategory("Books", "books");
        productRepository.saveAndFlush(product("KB-001", "keyboard", category));

        assertThatThrownBy(() -> categoryService.delete(category.getId()))
                .isInstanceOf(CategoryInUseException.class);
        assertThat(categoryRepository.findById(category.getId())).isPresent();
    }

    @Test
    void reassignAndDeleteMovesProductsToTheTargetCategory() {
        Category source = persistedCategory("Books", "books");
        Category target = persistedCategory("Media", "media");
        Long productId = productRepository.saveAndFlush(product("KB-001", "keyboard", source)).getId();

        categoryService.reassignAndDelete(source.getId(), target.getId());
        entityManager.flush();
        entityManager.clear();

        Product moved = productRepository.findWithCategoryBySlug("keyboard").orElseThrow();
        assertThat(moved.getId()).isEqualTo(productId);
        assertThat(moved.getCategory().getId()).isEqualTo(target.getId());
        assertThat(moved.getVersion()).isEqualTo(1L);
        assertThat(categoryRepository.findById(source.getId())).isEmpty();
    }

    @Test
    void reassignProcessesProductsInBoundedBatchesWithoutLeavingStragglers() {
        Category source = persistedCategory("Books", "books");
        Category target = persistedCategory("Media", "media");
        for (int i = 0; i < 5; i++) {
            productRepository.saveAndFlush(product("SKU-" + i, "slug-" + i, source));
        }

        ProductCategoryReference reference =
                new ProductCategoryReference(productRepository, categoryRepository, 2);
        assertThat(reference.reassign(source.getId(), target.getId())).isEqualTo(5);
        entityManager.flush();
        entityManager.clear();

        assertThat(productRepository.countByCategoryId(source.getId())).isZero();
        assertThat(productRepository.countByCategoryId(target.getId())).isEqualTo(5);
        assertThat(productRepository.findByCategoryId(target.getId(), PageRequest.of(0, 10)))
                .extracting(Product::getVersion)
                .containsOnly(1L);
    }

    @Test
    void incrementsVersionOnUpdateAndRejectsStaleWrites() {
        Category category = persistedCategory("Books", "books");
        Product saved = productRepository.saveAndFlush(product("KB-001", "keyboard", category));
        entityManager.detach(saved);

        Product current = productRepository.findById(saved.getId()).orElseThrow();
        current.changePrice(new BigDecimal("59.00"), CurrencyCode.EUR);
        productRepository.saveAndFlush(current);
        assertThat(current.getVersion()).isEqualTo(1L);

        saved.changePrice(new BigDecimal("10.00"), CurrencyCode.EUR);
        assertThatThrownBy(() -> productRepository.saveAndFlush(saved))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void categoryIsLazyByDefaultAndInitializedByTheEntityGraph() {
        Category category = persistedCategory("Books", "books");
        productRepository.saveAndFlush(product("KB-001", "keyboard", category));
        entityManager.clear();

        Product lazy = productRepository.findBySlug("keyboard").orElseThrow();
        assertThat(Hibernate.isInitialized(lazy.getCategory())).isFalse();
        entityManager.clear();

        Product fetched = productRepository.findWithCategoryBySlug("keyboard").orElseThrow();
        assertThat(Hibernate.isInitialized(fetched.getCategory())).isTrue();
        assertThat(fetched.getCategory().getName()).isEqualTo("Books");
    }

    @Test
    void activeCatalogPageFetchesCategoriesInTheSameQuery() {
        Category category = persistedCategory("Books", "books");
        productRepository.saveAndFlush(product("KB-001", "keyboard", category));
        Product hidden = product("KB-002", "mouse", category);
        hidden.deactivate();
        productRepository.saveAndFlush(hidden);
        entityManager.clear();

        var page = productRepository.findAll(
                ProductSpecifications.from(ProductSearchCriteria.of(null, null, null, null, 0, 10, null)),
                PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        Product listed = page.getContent().getFirst();
        assertThat(listed.getSlug()).isEqualTo("keyboard");
        assertThat(Hibernate.isInitialized(listed.getCategory())).isTrue();
    }

    @Test
    void catalogSearchHonorsFiltersSortAndPageBounds() {
        Category books = persistedCategory("Books", "books");
        Category games = persistedCategory("Games", "games");
        productRepository.saveAndFlush(pricedProduct("A-001", "Zebra Laptop", "zebra", "30.00", books));
        productRepository.saveAndFlush(pricedProduct("B-001", "Alpha Mouse", "alpha", "10.00", books));
        productRepository.saveAndFlush(pricedProduct("C-001", "Middle Pad", "middle", "20.00", books));
        productRepository.saveAndFlush(pricedProduct("G-001", "Laptop Game", "laptop-game", "25.00", games));
        Product inactive = pricedProduct("X-001", "Laptop Hidden", "laptop-hidden", "15.00", books);
        inactive.deactivate();
        productRepository.saveAndFlush(inactive);
        entityManager.clear();

        var firstPage = productRepository.findAll(
                ProductSpecifications.from(ProductSearchCriteria.of(
                        "books", new BigDecimal("10.00"), new BigDecimal("30.00"), "laptop", 0, 2, null)),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "price").and(Sort.by("id"))));
        var secondPage = productRepository.findAll(
                ProductSpecifications.from(ProductSearchCriteria.of(
                        "books", new BigDecimal("10.00"), new BigDecimal("30.00"), "laptop", 1, 2, null)),
                PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "price").and(Sort.by("id"))));

        assertThat(firstPage.getTotalElements()).isEqualTo(1);
        assertThat(firstPage.getContent()).extracting(Product::getSku).containsExactly("A-001");
        assertThat(secondPage.getContent()).isEmpty();

        var byCategoryAndPrice = productRepository.findAll(
                ProductSpecifications.from(ProductSearchCriteria.of(
                        "books", new BigDecimal("15.00"), new BigDecimal("25.00"), null, 0, 10, null)),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "price").and(Sort.by("id"))));
        assertThat(byCategoryAndPrice.getContent()).extracting(Product::getSku).containsExactly("C-001");
    }

    @Test
    void databaseDefinesProductConstraintsAndIndexes() {
        List<String> constraints = jdbcTemplate.queryForList(
                "select conname from pg_constraint where conrelid = 'products'::regclass",
                String.class);
        List<String> indexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where tablename = 'products'",
                String.class);

        assertThat(constraints).contains(
                PersistenceConventions.primaryKeyName("products"),
                PersistenceConventions.uniqueConstraintName("products", "sku"),
                PersistenceConventions.uniqueConstraintName("products", "slug"),
                PersistenceConventions.foreignKeyName("products", "categories"),
                PersistenceConventions.checkConstraintName("products", "price_non_negative"),
                PersistenceConventions.checkConstraintName("products", "stock_quantity_non_negative"),
                PersistenceConventions.checkConstraintName("products", "slug_format"));
        assertThat(indexes).contains(
                PersistenceConventions.indexName("products", "category_id"),
                PersistenceConventions.indexName("products", "active"),
                PersistenceConventions.indexName("products", "price"),
                PersistenceConventions.indexName("products", "name"),
                "ix_products_search_text_trgm",
                "ix_products_active_category_price");
    }

    private Category persistedCategory(String name, String slug) {
        return categoryRepository.saveAndFlush(Category.create(name, slug, null));
    }

    private static Product product(String sku, String slug, Category category) {
        return Product.create(
                sku, "Keyboard", slug, "Mechanical", new BigDecimal("49.50"), null, 5, category);
    }

    private static Product pricedProduct(String sku, String name, String slug, String price, Category category) {
        return Product.create(sku, name, slug, null, new BigDecimal(price), null, 5, category);
    }

    private void insertProduct(String sku, String slug, String price, int stock, Long categoryId) {
        jdbcTemplate.update(
                """
                insert into products (
                    sku, name, slug, description, price, currency, stock_quantity,
                    category_id, active, created_at, updated_at, version
                ) values (?, ?, ?, null, ?::numeric, 'EUR', ?, ?, true, now(), now(), 0)
                """,
                sku,
                "Keyboard",
                slug,
                price,
                stock,
                categoryId);
    }
}
