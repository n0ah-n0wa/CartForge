package com.example.ecommerce.cart.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.entity.CartItem;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.hibernate.Hibernate;
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
class CartRepositoryTest {

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
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesCartWithIdentityAndTimestamps() {
        Instant beforePersist = Instant.now().minusSeconds(1);
        User customer = persistedUser("ada@example.com");

        Cart saved = cartRepository.saveAndFlush(Cart.forUser(customer));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isAfter(beforePersist);
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
        assertThat(cartRepository.existsByUserId(customer.getId())).isTrue();
        assertThat(cartRepository.findByUserId(customer.getId())).isPresent();
    }

    @Test
    void allowsOnlyOneCartPerCustomer() {
        User customer = persistedUser("ada@example.com");
        cartRepository.saveAndFlush(Cart.forUser(customer));

        assertThatThrownBy(() -> cartRepository.saveAndFlush(Cart.forUser(customer)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_carts_user_id");
    }

    @Test
    void cascadesLinesWhenTheCartIsPersisted() {
        Cart cart = Cart.forUser(persistedUser("ada@example.com"));
        Product keyboard = persistedProduct("KB-001", "keyboard");
        cart.addOrIncrease(keyboard, 2);

        Cart saved = cartRepository.saveAndFlush(cart);
        entityManager.clear();

        List<CartItem> lines = cartItemRepository.findByCartId(saved.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void removingALineDeletesItThroughOrphanRemoval() {
        Cart cart = Cart.forUser(persistedUser("ada@example.com"));
        Product keyboard = persistedProduct("KB-001", "keyboard");
        cart.addOrIncrease(keyboard, 2);
        Cart saved = cartRepository.saveAndFlush(cart);

        saved.removeItem(keyboard);
        cartRepository.saveAndFlush(saved);
        entityManager.clear();

        assertThat(cartItemRepository.findByCartId(saved.getId())).isEmpty();
        assertThat(productRepository.findBySku("KB-001")).isPresent();
    }

    @Test
    void clearingTheCartDeletesEveryLineButKeepsTheCart() {
        Cart cart = Cart.forUser(persistedUser("ada@example.com"));
        cart.addOrIncrease(persistedProduct("KB-001", "keyboard"), 1);
        cart.addOrIncrease(persistedProduct("MS-001", "mouse"), 1);
        Cart saved = cartRepository.saveAndFlush(cart);

        saved.clear();
        cartRepository.saveAndFlush(saved);
        entityManager.clear();

        assertThat(cartItemRepository.findByCartId(saved.getId())).isEmpty();
        assertThat(cartRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void deletingTheCartRemovesItsLinesButNotTheCustomerOrProducts() {
        User customer = persistedUser("ada@example.com");
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(persistedProduct("KB-001", "keyboard"), 1);
        Long cartId = cartRepository.saveAndFlush(cart).getId();

        cartRepository.deleteById(cartId);
        entityManager.flush();
        entityManager.clear();

        assertThat(cartItemRepository.findByCartId(cartId)).isEmpty();
        assertThat(userRepository.findById(customer.getId())).isPresent();
        assertThat(productRepository.findBySku("KB-001")).isPresent();
    }

    @Test
    void databaseCascadesLinesWhenACartRowIsDeleted() {
        Cart cart = Cart.forUser(persistedUser("ada@example.com"));
        cart.addOrIncrease(persistedProduct("KB-001", "keyboard"), 1);
        Long cartId = cartRepository.saveAndFlush(cart).getId();
        entityManager.clear();

        jdbcTemplate.update("delete from carts where id = ?", cartId);

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from cart_items where cart_id = ?", Integer.class, cartId))
                .isZero();
    }

    @Test
    void databaseRejectsDuplicateProductWithinOneCart() {
        Cart cart = Cart.forUser(persistedUser("ada@example.com"));
        Product keyboard = persistedProduct("KB-001", "keyboard");
        cart.addOrIncrease(keyboard, 1);
        Long cartId = cartRepository.saveAndFlush(cart).getId();
        entityManager.clear();

        assertThatThrownBy(() -> insertCartItem(cartId, keyboard.getId(), 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_cart_items_cart_id_product_id");
    }

    @Test
    void databaseRejectsNonPositiveQuantity() {
        Cart cart = Cart.forUser(persistedUser("ada@example.com"));
        Long cartId = cartRepository.saveAndFlush(cart).getId();
        Long productId = persistedProduct("KB-001", "keyboard").getId();
        entityManager.flush();

        assertThatThrownBy(() -> insertCartItem(cartId, productId, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cart_items_quantity_positive");
    }

    @Test
    void databaseRestrictsDeletingAProductThatSitsInACart() {
        Cart cart = Cart.forUser(persistedUser("ada@example.com"));
        Product keyboard = persistedProduct("KB-001", "keyboard");
        cart.addOrIncrease(keyboard, 1);
        cartRepository.saveAndFlush(cart);
        entityManager.clear();

        assertThatThrownBy(() -> jdbcTemplate.update("delete from products where id = ?", keyboard.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_cart_items_products");
    }

    @Test
    void databaseRestrictsDeletingACustomerWhoStillHasACart() {
        User customer = persistedUser("ada@example.com");
        cartRepository.saveAndFlush(Cart.forUser(customer));
        entityManager.clear();

        assertThatThrownBy(() -> jdbcTemplate.update("delete from users where id = ?", customer.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_carts_users");
    }

    @Test
    void findsALineByCartAndProduct() {
        Cart cart = Cart.forUser(persistedUser("ada@example.com"));
        Product keyboard = persistedProduct("KB-001", "keyboard");
        cart.addOrIncrease(keyboard, 3);
        Long cartId = cartRepository.saveAndFlush(cart).getId();
        entityManager.clear();

        assertThat(cartItemRepository.findByCartIdAndProductId(cartId, keyboard.getId()))
                .isPresent()
                .get()
                .extracting(CartItem::getQuantity)
                .isEqualTo(3);
        assertThat(cartItemRepository.countByProductId(keyboard.getId())).isEqualTo(1);
    }

    @Test
    void linesAndProductsAreLazyByDefaultAndFetchedByTheEntityGraph() {
        User customer = persistedUser("ada@example.com");
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(persistedProduct("KB-001", "keyboard"), 1);
        cartRepository.saveAndFlush(cart);
        entityManager.clear();

        Cart lazy = cartRepository.findByUserId(customer.getId()).orElseThrow();
        assertThat(Hibernate.isInitialized(lazy.getUser())).isFalse();
        entityManager.clear();

        Cart fetched = cartRepository.findWithItemsByUserId(customer.getId()).orElseThrow();
        List<CartItem> items = fetched.getItems();
        assertThat(items).hasSize(1);
        assertThat(Hibernate.isInitialized(items.get(0).getProduct())).isTrue();
        assertThat(items.get(0).getProduct().getSku()).isEqualTo("KB-001");
    }

    @Test
    void matchingAnExistingLineDoesNotInitialiseUnrelatedProducts() {
        User customer = persistedUser("ada@example.com");
        Cart cart = Cart.forUser(customer);
        cart.addOrIncrease(persistedProduct("KB-001", "keyboard"), 1);
        cart.addOrIncrease(persistedProduct("MS-001", "mouse"), 1);
        cart.addOrIncrease(persistedProduct("HS-001", "headset"), 1);
        cartRepository.saveAndFlush(cart);
        entityManager.clear();

        Cart reloaded = cartRepository.findByUserId(customer.getId()).orElseThrow();
        List<CartItem> lines = reloaded.getItems();
        Product keyboard = productRepository.findBySku("KB-001").orElseThrow();

        reloaded.addOrIncrease(keyboard, 2);

        assertThat(reloaded.getItems()).hasSize(3);
        assertThat(lines.stream()
                        .filter(line -> !Hibernate.isInitialized(line.getProduct()))
                        .count())
                .as("unrelated cart lines must not load their product just to compare ids")
                .isEqualTo(2);
    }

    @Test
    void databaseDefinesCartConstraintsAndIndexes() {
        List<String> cartConstraints = jdbcTemplate.queryForList(
                "select conname from pg_constraint where conrelid = 'carts'::regclass", String.class);
        List<String> itemConstraints = jdbcTemplate.queryForList(
                "select conname from pg_constraint where conrelid = 'cart_items'::regclass", String.class);
        List<String> itemIndexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where tablename = 'cart_items'", String.class);

        assertThat(cartConstraints).contains(
                PersistenceConventions.primaryKeyName("carts"),
                PersistenceConventions.uniqueConstraintName("carts", "user_id"),
                PersistenceConventions.foreignKeyName("carts", "users"));
        assertThat(itemConstraints).contains(
                PersistenceConventions.primaryKeyName("cart_items"),
                PersistenceConventions.uniqueConstraintName("cart_items", "cart_id", "product_id"),
                PersistenceConventions.foreignKeyName("cart_items", "carts"),
                PersistenceConventions.foreignKeyName("cart_items", "products"),
                PersistenceConventions.checkConstraintName("cart_items", "quantity_positive"));
        assertThat(itemIndexes).contains(
                PersistenceConventions.uniqueConstraintName("cart_items", "cart_id", "product_id"),
                PersistenceConventions.indexName("cart_items", "product_id"));
    }

    private User persistedUser(String email) {
        return userRepository.saveAndFlush(User.registerCustomer(
                email, "test-only-password-hash", "Ada", "Lovelace"));
    }

    private Product persistedProduct(String sku, String slug) {
        Category category = categoryRepository.findBySlug("books")
                .orElseGet(() -> categoryRepository.saveAndFlush(Category.create("Books", "books", null)));
        return productRepository.saveAndFlush(Product.create(
                sku, "Keyboard", slug, null, new BigDecimal("49.50"), null, 10, category));
    }

    private void insertCartItem(Long cartId, Long productId, int quantity) {
        jdbcTemplate.update(
                """
                insert into cart_items (cart_id, product_id, quantity, created_at, updated_at)
                values (?, ?, ?, now(), now())
                """,
                cartId,
                productId,
                quantity);
    }
}
