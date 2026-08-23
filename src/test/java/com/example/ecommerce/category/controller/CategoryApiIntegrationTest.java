package com.example.ecommerce.category.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
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
class CategoryApiIntegrationTest {

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

    @BeforeEach
    void cleanCatalog() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    @Test
    void publicListingRequiresNoAuthentication() throws Exception {
        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", "Printed titles"));
        Category hidden = Category.create("Archived", "archived", null);
        hidden.deactivate();
        categoryRepository.saveAndFlush(hidden);

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(books.getId()))
                .andExpect(jsonPath("$[0].slug").value("books"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void publicRetrievalReturnsActiveCategoriesOnly() throws Exception {
        Category saved = categoryRepository.saveAndFlush(Category.create("Books", "books", null));

        mockMvc.perform(get("/api/v1/categories/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Books"))
                .andExpect(jsonPath("$.slug").value("books"));
    }

    @Test
    void publicRetrievalOfInactiveCategoryReturnsNotFound() throws Exception {
        Category hidden = Category.create("Archived", "archived", null);
        hidden.deactivate();
        hidden = categoryRepository.saveAndFlush(hidden);

        mockMvc.perform(get("/api/v1/categories/" + hidden.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void administratorsCanRetrieveInactiveCategories() throws Exception {
        Category hidden = Category.create("Archived", "archived", null);
        hidden.deactivate();
        hidden = categoryRepository.saveAndFlush(hidden);

        mockMvc.perform(get("/api/v1/categories/" + hidden.getId()).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void missingCategoryReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/categories/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/v1/categories/99999"));
    }

    @Test
    void administratorCreatesCategory() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"name":"Books","slug":"books","description":"Printed titles"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.name").value("Books"))
                .andExpect(jsonPath("$.slug").value("books"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void customersCannotCreateCategories() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, customer())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"name":"Books","slug":"books"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedWritesAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"name":"Books","slug":"books"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void validationRejectsInvalidPayloads() throws Exception {
        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"name":"","slug":"Not URL Safe"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/v1/categories/1")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void duplicateNameAndSlugAreRejected() throws Exception {
        categoryRepository.saveAndFlush(Category.create("Books", "books", null));

        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"name":"Books","slug":"paper"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CATEGORY_NAME"));

        mockMvc.perform(post("/api/v1/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"name":"Paper","slug":"books"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CATEGORY_SLUG"));
    }

    @Test
    void administratorUpdatesAndPatchesCategories() throws Exception {
        Category saved = categoryRepository.saveAndFlush(Category.create("Books", "books", "Old"));

        mockMvc.perform(put("/api/v1/categories/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"name":"Media","slug":"media","description":"Updated","active":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Media"))
                .andExpect(jsonPath("$.slug").value("media"));

        mockMvc.perform(patch("/api/v1/categories/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"active":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/v1/categories/" + saved.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void customersCannotModifyCategories() throws Exception {
        Category saved = categoryRepository.saveAndFlush(Category.create("Books", "books", null));

        mockMvc.perform(put("/api/v1/categories/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, customer())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"name":"Media","slug":"media","description":null,"active":true}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/categories/" + saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, customer())
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"active":false}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/categories/" + saved.getId()).header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteRemovesUnusedCategories() throws Exception {
        Category saved = categoryRepository.saveAndFlush(Category.create("Books", "books", null));

        mockMvc.perform(delete("/api/v1/categories/" + saved.getId()).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/categories")).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void deleteIsRejectedWhenProductsReferenceTheCategory() throws Exception {
        Category saved = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        productRepository.saveAndFlush(Product.create(
                "BOOK-001",
                "Book",
                "book",
                null,
                new BigDecimal("9.99"),
                CurrencyCode.EUR,
                5,
                saved));

        mockMvc.perform(delete("/api/v1/categories/" + saved.getId()).header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));

        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].slug", containsInAnyOrder("books")));
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
