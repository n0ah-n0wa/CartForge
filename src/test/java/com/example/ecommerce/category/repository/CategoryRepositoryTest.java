package com.example.ecommerce.category.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.category.dto.CreateCategoryCommand;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.service.CategoryNotFoundException;
import com.example.ecommerce.category.service.CategoryService;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
class CategoryRepositoryTest {

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
    private CategoryRepository categoryRepository;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesIdentityTimestampsAndActiveDefault() {
        Instant beforePersist = Instant.now().minusSeconds(1);

        Category saved = categoryRepository.saveAndFlush(Category.create("Books", "books", "Printed titles"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCreatedAt()).isAfter(beforePersist);
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
        assertThat(categoryRepository.findBySlug("books"))
                .isPresent()
                .get()
                .extracting(Category::getName)
                .isEqualTo("Books");
    }

    @Test
    void listsOnlyActiveCategories() {
        categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        Category hidden = Category.create("Archived", "archived", null);
        hidden.deactivate();
        categoryRepository.saveAndFlush(hidden);

        assertThat(categoryRepository.findByActiveTrueOrderByNameAsc())
                .extracting(Category::getSlug)
                .containsExactly("books");
    }

    @Test
    void rejectsDuplicateName() {
        categoryRepository.saveAndFlush(Category.create("Books", "books", null));

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(Category.create("Books", "paper", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateSlug() {
        categoryRepository.saveAndFlush(Category.create("Books", "books", null));

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(Category.create("Paper", "books", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsInvalidSlug() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        insert into categories (name, slug, description, active, created_at, updated_at)
                        values (?, ?, null, true, now(), now())
                        """,
                        "Invalid",
                        "Not URL Safe"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseDefinesUniqueAndCheckConstraints() {
        List<String> constraints = jdbcTemplate.queryForList(
                "select conname from pg_constraint where conrelid = 'categories'::regclass",
                String.class);

        assertThat(constraints).contains(
                PersistenceConventions.primaryKeyName("categories"),
                PersistenceConventions.uniqueConstraintName("categories", "name"),
                PersistenceConventions.uniqueConstraintName("categories", "slug"),
                PersistenceConventions.checkConstraintName("categories", "slug_format"));
    }

    @Test
    void deleteSucceedsWhenNoProductsReferenceTheCategory() {
        Category saved = categoryService.create(new CreateCategoryCommand("Books", "books", null));

        categoryService.delete(saved.getId());

        assertThat(categoryRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    void deactivateIsPreferredOverDeleteWhenKeepingTheRow() {
        Category saved = categoryService.create(new CreateCategoryCommand("Books", "books", null));

        categoryService.deactivate(saved.getId());

        Category reloaded = categoryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isActive()).isFalse();
        assertThat(categoryRepository.findByActiveTrueOrderByNameAsc()).isEmpty();
    }

    @Test
    void reassignAndDeleteRequiresAnExistingTarget() {
        Category source = categoryService.create(new CreateCategoryCommand("Books", "books", null));

        assertThatThrownBy(() -> categoryService.reassignAndDelete(source.getId(), 9_999L))
                .isInstanceOf(CategoryNotFoundException.class);
        assertThat(categoryRepository.findById(source.getId())).isPresent();
    }

    @Test
    void reassignAndDeleteRemovesSourceWhenTargetExists() {
        Category source = categoryService.create(new CreateCategoryCommand("Books", "books", null));
        Category target = categoryService.create(new CreateCategoryCommand("Media", "media", null));

        categoryService.reassignAndDelete(source.getId(), target.getId());

        assertThat(categoryRepository.findById(source.getId())).isEmpty();
        assertThat(categoryRepository.findById(target.getId())).isPresent();
    }
}
