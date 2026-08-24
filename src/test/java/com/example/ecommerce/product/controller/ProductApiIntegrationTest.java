package com.example.ecommerce.product.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.UserRole;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
@Transactional
class ProductApiIntegrationTest {

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
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    private Category books;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
    }

    @Test
    void publicListingRequiresNoAuthenticationAndOmitsInactiveProducts() throws Exception {
        productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));
        Product hidden = activeProduct("KB-002", "Hidden", "hidden");
        hidden.deactivate();
        productRepository.saveAndFlush(hidden);

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("KB-001"))
                .andExpect(jsonPath("$.content[0].purchasable").value(true))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void publicListingSupportsPaginationAndAllowlistedSorting() throws Exception {
        productRepository.saveAndFlush(pricedProduct("P-HIGH", "Zebra", "zebra", "30.00"));
        productRepository.saveAndFlush(pricedProduct("P-LOW", "Alpha", "alpha", "10.00"));
        productRepository.saveAndFlush(pricedProduct("P-MID", "Middle", "middle", "20.00"));

        mockMvc.perform(get("/api/v1/products")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "price,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].sku").value("P-LOW"))
                .andExpect(jsonPath("$.content[1].sku").value("P-MID"));

        mockMvc.perform(get("/api/v1/products")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "price,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("P-HIGH"));
    }

    @Test
    void publicListingCapsPageSizeAndRejectsInvalidSort() throws Exception {
        productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(get("/api/v1/products").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(get("/api/v1/products").param("sort", "password,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));

        mockMvc.perform(get("/api/v1/products").param("sort", "price,sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));

        mockMvc.perform(get("/api/v1/products").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void publicListingSupportsCombinedFiltersSearchAndSorting() throws Exception {
        Category games = categoryRepository.saveAndFlush(Category.create("Games", "games", null));
        productRepository.saveAndFlush(describedProduct(
                "A-001", "Zebra Laptop", "zebra", "Portable computer", "30.00", books));
        productRepository.saveAndFlush(describedProduct(
                "B-001", "Alpha Mouse", "alpha", "Pointing device", "10.00", books));
        productRepository.saveAndFlush(describedProduct(
                "C-001", "Middle Pad", "middle", "Desk accessory", "20.00", books));
        productRepository.saveAndFlush(describedProduct(
                "G-001", "Laptop Game", "laptop-game", "Console title", "25.00", games));
        Product inactive = describedProduct(
                "X-001", "Laptop Hidden", "laptop-hidden", "Should not appear", "15.00", books);
        inactive.deactivate();
        productRepository.saveAndFlush(inactive);

        mockMvc.perform(get("/api/v1/products")
                        .param("category", "books")
                        .param("minPrice", "15")
                        .param("maxPrice", "30")
                        .param("search", "laptop")
                        .param("sort", "price,asc")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("A-001"))
                .andExpect(jsonPath("$.content[0].category.slug").value("books"));

        mockMvc.perform(get("/api/v1/products")
                        .param("category", "books")
                        .param("minPrice", "10")
                        .param("maxPrice", "25")
                        .param("sort", "price,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].sku").value("C-001"))
                .andExpect(jsonPath("$.content[1].sku").value("B-001"));

        mockMvc.perform(get("/api/v1/products").param("search", "MOUSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("B-001"));

        mockMvc.perform(get("/api/v1/products").param("category", "missing-category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void publicListingRejectsInvalidFilterParameters() throws Exception {
        productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(get("/api/v1/products").param("minPrice", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/products")
                        .param("minPrice", "50")
                        .param("maxPrice", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/products").param("maxPrice", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/products").param("search", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/products").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void publicRetrievalReturnsActiveProductsOnly() throws Exception {
        Product saved = productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(get("/api/v1/products/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("KB-001"))
                .andExpect(jsonPath("$.category.slug").value("books"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void publicRetrievalOfInactiveProductReturnsNotFound() throws Exception {
        Product hidden = activeProduct("KB-002", "Hidden", "hidden");
        hidden.deactivate();
        hidden = productRepository.saveAndFlush(hidden);

        mockMvc.perform(get("/api/v1/products/" + hidden.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void administratorsCanRetrieveInactiveProducts() throws Exception {
        Product hidden = activeProduct("KB-002", "Hidden", "hidden");
        hidden.deactivate();
        hidden = productRepository.saveAndFlush(hidden);

        mockMvc.perform(get("/api/v1/products/" + hidden.getId()).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.purchasable").value(false));
    }

    @Test
    void publicListingRejectsActiveFilter() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("active", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void administratorsCanFilterByActiveStatus() throws Exception {
        productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));
        Product hidden = activeProduct("KB-002", "Hidden", "hidden");
        hidden.deactivate();
        productRepository.saveAndFlush(hidden);

        mockMvc.perform(get("/api/v1/products")
                        .param("active", "false")
                        .header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("KB-002"))
                .andExpect(jsonPath("$.content[0].active").value(false));

        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void administratorCreatesProduct() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "sku":"kb-100",
                                  "name":"Keyboard",
                                  "slug":"keyboard",
                                  "description":"Mechanical",
                                  "price":49.50,
                                  "currency":"EUR",
                                  "stockQuantity":5,
                                  "categoryId":%d
                                }
                                """.formatted(books.getId())))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.containsString("/api/v1/products/")))
                .andExpect(jsonPath("$.sku").value("KB-100"))
                .andExpect(jsonPath("$.price").value(49.50))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.purchasable").value(true));
    }

    @Test
    void createRequiresExplicitCurrency() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"sku":"KB-CUR","name":"Keyboard","slug":"keyboard-cur","price":10.00,"stockQuantity":1,"categoryId":%d}
                                """.formatted(books.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void customersCannotCreateProducts() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, customer())
                        .contentType(APPLICATION_JSON)
                        .content(createBody(books.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void validationRejectsNegativePriceAndStock() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "sku":"BAD",
                                  "name":"Bad",
                                  "slug":"bad",
                                  "price":-1,
                                  "currency":"EUR",
                                  "stockQuantity":-3,
                                  "categoryId":%d
                                }
                                """.formatted(books.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createRejectsUnknownAndInactiveCategories() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(createBody(99999L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRODUCT_CATEGORY"));

        Category inactive = Category.create("Archived", "archived", null);
        inactive.deactivate();
        inactive = categoryRepository.saveAndFlush(inactive);

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(createBody(inactive.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRODUCT_CATEGORY"));
    }

    @Test
    void duplicateSkuAndSlugAreRejected() throws Exception {
        productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"sku":"kb-001","name":"Other","slug":"other","price":1.00,"currency":"EUR","stockQuantity":1,"categoryId":%d}
                                """.formatted(books.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PRODUCT_SKU"));

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"sku":"KB-002","name":"Other","slug":"keyboard","price":1.00,"currency":"EUR","stockQuantity":1,"categoryId":%d}
                                """.formatted(books.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PRODUCT_SLUG"));
    }

    @Test
    void administratorUpdatesAndPatchesWithOptimisticLocking() throws Exception {
        Product saved = productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(put("/api/v1/products/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {
                                  "version":0,
                                  "name":"Keyboard Pro",
                                  "slug":"keyboard-pro",
                                  "description":"Updated",
                                  "price":59.00,
                                  "currency":"EUR",
                                  "stockQuantity":2,
                                  "categoryId":%d,
                                  "active":true
                                }
                                """.formatted(books.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Keyboard Pro"))
                .andExpect(jsonPath("$.slug").value("keyboard-pro"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(patch("/api/v1/products/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"version":1,"active":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.purchasable").value(false));

        mockMvc.perform(get("/api/v1/products/" + saved.getId())).andExpect(status().isNotFound());
    }

    @Test
    void staleVersionIsRejected() throws Exception {
        Product saved = productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(patch("/api/v1/products/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"version":99,"price":12.00}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_VERSION_CONFLICT"));
    }

    @Test
    void deleteDeactivatesTheProduct() throws Exception {
        Product saved = productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(delete("/api/v1/products/" + saved.getId())
                        .param("version", "0")
                        .header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/products")).andExpect(jsonPath("$.content").isEmpty());
        mockMvc.perform(get("/api/v1/products/" + saved.getId()).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void customersCannotModifyProducts() throws Exception {
        Product saved = productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(put("/api/v1/products/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, customer())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"version":0,"name":"X","slug":"x","price":1,"currency":"EUR","stockQuantity":1,"categoryId":%d,"active":true}
                                """.formatted(books.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(patch("/api/v1/products/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, customer())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"version":0,"active":false}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(delete("/api/v1/products/" + saved.getId())
                        .param("version", "0")
                        .header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deleteRequiresVersionQueryParameter() throws Exception {
        Product saved = productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(delete("/api/v1/products/" + saved.getId()).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("version is required"));

        mockMvc.perform(delete("/api/v1/products/" + saved.getId())
                        .param("version", "not-a-number")
                        .header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("version is invalid"));
    }

    @Test
    void emptyPatchAndMalformedBodyAreRejected() throws Exception {
        Product saved = productRepository.saveAndFlush(activeProduct("KB-001", "Keyboard", "keyboard"));

        mockMvc.perform(patch("/api/v1/products/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"));
    }

    @Test
    void unauthenticatedWritesReturnStandardErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/products")
                        .contentType(APPLICATION_JSON)
                        .content(createBody(books.getId())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"));
    }

    @Test
    void missingProductReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/products/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    private Product activeProduct(String sku, String name, String slug) {
        return Product.create(sku, name, slug, null, new BigDecimal("19.99"), CurrencyCode.EUR, 3, books);
    }

    private Product pricedProduct(String sku, String name, String slug, String price) {
        return Product.create(sku, name, slug, null, new BigDecimal(price), CurrencyCode.EUR, 3, books);
    }

    private Product describedProduct(
            String sku, String name, String slug, String description, String price, Category category) {
        return Product.create(sku, name, slug, description, new BigDecimal(price), CurrencyCode.EUR, 3, category);
    }

    private static String createBody(Long categoryId) {
        return """
                {"sku":"KB-100","name":"Keyboard","slug":"keyboard","price":49.50,"currency":"EUR","stockQuantity":5,"categoryId":%d}
                """.formatted(categoryId);
    }

    private String customer() {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(1L, "ada@example.com", UserRole.CUSTOMER))
                .accessToken();
    }

    private String admin() {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(2L, "root@example.com", UserRole.ADMIN))
                .accessToken();
    }
}
