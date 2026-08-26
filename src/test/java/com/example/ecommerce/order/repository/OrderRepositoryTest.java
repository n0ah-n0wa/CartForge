package com.example.ecommerce.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.order.entity.OrderItem;
import com.example.ecommerce.order.mapper.OrderMapper;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
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
class OrderRepositoryTest {

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
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

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
    void savesOrderWithIdentityTimestampsVersionAndPendingStatus() {
        Instant beforePersist = Instant.now().minusSeconds(1);
        Order order = newOrder("ORD-2026-000001", persistedUser("ada@example.com"));
        order.addItem(persistedProduct("KB-001", "Keyboard", "keyboard", "49.50"), 2);

        Order saved = orderRepository.saveAndFlush(order);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getCurrency()).isEqualTo(CurrencyCode.EUR);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("99.00");
        assertThat(saved.getCreatedAt()).isAfter(beforePersist);
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
    }

    @Test
    void rejectsDuplicateOrderNumber() {
        User customer = persistedUser("ada@example.com");
        orderRepository.saveAndFlush(newOrder("ORD-2026-000001", customer));

        assertThatThrownBy(() -> orderRepository.saveAndFlush(newOrder("ORD-2026-000001", customer)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_orders_order_number");
    }

    @Test
    void lineSnapshotSurvivesLaterProductChanges() {
        User customer = persistedUser("ada@example.com");
        Product keyboard = persistedProduct("KB-001", "Keyboard", "keyboard", "49.50");
        Order order = newOrder("ORD-2026-000001", customer);
        order.addItem(keyboard, 2);
        orderRepository.saveAndFlush(order);

        keyboard.rename("Keyboard Pro", "keyboard-pro");
        keyboard.changePrice(new BigDecimal("99.99"), CurrencyCode.EUR);
        keyboard.deactivate();
        productRepository.saveAndFlush(keyboard);
        entityManager.clear();

        Order reloaded = orderRepository.findWithItemsByOrderNumber("ORD-2026-000001").orElseThrow();
        OrderItem line = reloaded.getItems().get(0);

        assertThat(line.getProductName()).isEqualTo("Keyboard");
        assertThat(line.getSku()).isEqualTo("KB-001");
        assertThat(line.getUnitPrice()).isEqualByComparingTo("49.50");
        assertThat(line.getLineTotal()).isEqualByComparingTo("99.00");
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo("99.00");
        assertThat(productRepository.findBySku("KB-001").orElseThrow().getName())
                .isEqualTo("Keyboard Pro");
    }

    @Test
    void persistsStatusChangesAndIncrementsVersion() {
        Order saved = orderRepository.saveAndFlush(
                newOrder("ORD-2026-000001", persistedUser("ada@example.com")));

        saved.transitionTo(OrderStatus.CONFIRMED);
        orderRepository.saveAndFlush(saved);
        entityManager.clear();

        Order reloaded = orderRepository.findByOrderNumber("ORD-2026-000001").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(reloaded.getVersion()).isEqualTo(1L);
    }

    @Test
    void rejectsStaleWritesThroughOptimisticLocking() {
        Order saved = orderRepository.saveAndFlush(
                newOrder("ORD-2026-000001", persistedUser("ada@example.com")));
        entityManager.detach(saved);

        Order current = orderRepository.findById(saved.getId()).orElseThrow();
        current.transitionTo(OrderStatus.CONFIRMED);
        orderRepository.saveAndFlush(current);

        saved.cancel();
        assertThatThrownBy(() -> orderRepository.saveAndFlush(saved))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void scopesCustomerReadsToTheOwner() {
        User ada = persistedUser("ada@example.com");
        User grace = persistedUser("grace@example.com");
        Order order = orderRepository.saveAndFlush(newOrder("ORD-2026-000001", ada));
        orderRepository.saveAndFlush(newOrder("ORD-2026-000002", grace));
        entityManager.clear();

        assertThat(orderRepository.findByIdAndUserId(order.getId(), ada.getId())).isPresent();
        assertThat(orderRepository.findByIdAndUserId(order.getId(), grace.getId())).isEmpty();
        assertThat(orderRepository.findByOrderNumberAndUserId("ORD-2026-000001", grace.getId())).isEmpty();
        assertThat(orderRepository.findByUserId(ada.getId(), PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
    }

    @Test
    void findsOrdersByStatusForAdministrators() {
        User customer = persistedUser("ada@example.com");
        Order confirmed = orderRepository.saveAndFlush(newOrder("ORD-2026-000001", customer));
        confirmed.transitionTo(OrderStatus.CONFIRMED);
        orderRepository.saveAndFlush(confirmed);
        entityManager.clear();

        assertThat(orderRepository.findByStatus(OrderStatus.CONFIRMED, PageRequest.of(0, 10)))
                .extracting(Order::getOrderNumber)
                .containsExactly("ORD-2026-000001");
        assertThat(orderRepository.findByStatus(OrderStatus.SHIPPED, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void databaseRejectsAnUnknownStatus() {
        Long userId = persistedUser("ada@example.com").getId();
        entityManager.flush();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        insert into orders (
                            order_number, user_id, status, total_amount, currency,
                            shipping_address, created_at, updated_at, version
                        ) values (?, ?, ?, 0, 'EUR', '1 Main Street', now(), now(), 0)
                        """,
                        "ORD-2026-000999",
                        userId,
                        "ARCHIVED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_orders_status");
    }

    @Test
    void databaseRejectsALineTotalThatDoesNotMatchUnitPriceTimesQuantity() {
        Order order = newOrder("ORD-2026-000001", persistedUser("ada@example.com"));
        Long orderId = orderRepository.saveAndFlush(order).getId();
        Long productId = persistedProduct("KB-001", "Keyboard", "keyboard", "49.50").getId();
        entityManager.flush();

        assertThatThrownBy(() -> insertOrderItem(orderId, productId, "49.50", 2, "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_order_items_line_total");
    }

    @Test
    void databaseRejectsNonPositiveLineQuantity() {
        Order order = newOrder("ORD-2026-000001", persistedUser("ada@example.com"));
        Long orderId = orderRepository.saveAndFlush(order).getId();
        Long productId = persistedProduct("KB-001", "Keyboard", "keyboard", "49.50").getId();
        entityManager.flush();

        assertThatThrownBy(() -> insertOrderItem(orderId, productId, "49.50", 0, "0.00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_order_items_quantity_positive");
    }

    @Test
    void databaseRestrictsDeletingACustomerWhoHasOrders() {
        User customer = persistedUser("ada@example.com");
        Order order = newOrder("ORD-2026-000001", customer);
        order.addItem(persistedProduct("KB-001", "Keyboard", "keyboard", "49.50"), 1);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        assertThatThrownBy(() -> jdbcTemplate.update("delete from users where id = ?", customer.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_orders_users");
    }

    @Test
    void databaseRestrictsDeletingAProductThatAnOrderRecorded() {
        Product keyboard = persistedProduct("KB-001", "Keyboard", "keyboard", "49.50");
        Order order = newOrder("ORD-2026-000001", persistedUser("ada@example.com"));
        order.addItem(keyboard, 1);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        assertThatThrownBy(() -> jdbcTemplate.update("delete from products where id = ?", keyboard.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_order_items_products");
    }

    @Test
    void databaseCascadesLinesWhenAnOrderRowIsDeleted() {
        Order order = newOrder("ORD-2026-000001", persistedUser("ada@example.com"));
        order.addItem(persistedProduct("KB-001", "Keyboard", "keyboard", "49.50"), 1);
        Long orderId = orderRepository.saveAndFlush(order).getId();
        entityManager.clear();

        jdbcTemplate.update("delete from orders where id = ?", orderId);

        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from order_items where order_id = ?", Integer.class, orderId))
                .isZero();
    }

    @Test
    void linesAreLazyByDefaultAndFetchedByTheEntityGraph() {
        Order order = newOrder("ORD-2026-000001", persistedUser("ada@example.com"));
        order.addItem(persistedProduct("KB-001", "Keyboard", "keyboard", "49.50"), 1);
        Long orderId = orderRepository.saveAndFlush(order).getId();
        entityManager.clear();

        Order lazy = orderRepository.findById(orderId).orElseThrow();
        assertThat(Hibernate.isInitialized(lazy.getUser())).isFalse();
        entityManager.clear();

        Order fetched = orderRepository.findWithItemsByOrderNumber("ORD-2026-000001").orElseThrow();
        assertThat(Hibernate.isInitialized(fetched.getItems())).isTrue();
        assertThat(fetched.getItems()).hasSize(1);
        assertThat(orderItemRepository.findByOrderId(orderId)).hasSize(1);
    }

    @Test
    void countsHistoricalReferencesToAProduct() {
        Product keyboard = persistedProduct("KB-001", "Keyboard", "keyboard", "49.50");
        Product mouse = persistedProduct("MS-001", "Mouse", "mouse", "10.00");
        Order order = newOrder("ORD-2026-000001", persistedUser("ada@example.com"));
        order.addItem(keyboard, 1);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        assertThat(orderItemRepository.countByProductId(keyboard.getId())).isEqualTo(1);
        assertThat(orderItemRepository.countByProductId(mouse.getId())).isZero();
    }

    @Test
    void readingALineProductIdDoesNotInitialiseTheProduct() {
        Product keyboard = persistedProduct("KB-001", "Keyboard", "keyboard", "49.50");
        Long productId = keyboard.getId();
        Order order = newOrder("ORD-2026-000001", persistedUser("ada@example.com"));
        order.addItem(keyboard, 1);
        orderRepository.saveAndFlush(order);
        entityManager.clear();

        OrderItem line = orderRepository.findWithItemsByOrderNumber("ORD-2026-000001")
                .orElseThrow()
                .getItems()
                .get(0);

        assertThat(OrderMapper.toItemResponse(line).productId()).isEqualTo(productId);
        assertThat(Hibernate.isInitialized(line.getProduct())).isFalse();
    }

    @Test
    void databaseDefinesOrderConstraintsAndIndexes() {
        List<String> orderConstraints = jdbcTemplate.queryForList(
                "select conname from pg_constraint where conrelid = 'orders'::regclass", String.class);
        List<String> itemConstraints = jdbcTemplate.queryForList(
                "select conname from pg_constraint where conrelid = 'order_items'::regclass", String.class);
        List<String> orderIndexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where tablename = 'orders'", String.class);
        List<String> itemIndexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where tablename = 'order_items'", String.class);

        assertThat(orderConstraints).contains(
                PersistenceConventions.primaryKeyName("orders"),
                PersistenceConventions.uniqueConstraintName("orders", "order_number"),
                PersistenceConventions.foreignKeyName("orders", "users"),
                PersistenceConventions.checkConstraintName("orders", "status"),
                PersistenceConventions.checkConstraintName("orders", "total_amount_non_negative"));
        assertThat(itemConstraints).contains(
                PersistenceConventions.primaryKeyName("order_items"),
                PersistenceConventions.foreignKeyName("order_items", "orders"),
                PersistenceConventions.foreignKeyName("order_items", "products"),
                PersistenceConventions.checkConstraintName("order_items", "quantity_positive"),
                PersistenceConventions.checkConstraintName("order_items", "unit_price_non_negative"),
                PersistenceConventions.checkConstraintName("order_items", "line_total"));
        assertThat(orderIndexes).contains(
                PersistenceConventions.indexName("orders", "user_id"),
                PersistenceConventions.indexName("orders", "status"));
        assertThat(itemIndexes).contains(
                PersistenceConventions.indexName("order_items", "order_id"),
                PersistenceConventions.indexName("order_items", "product_id"));
    }

    private static Order newOrder(String orderNumber, User customer) {
        return Order.place(orderNumber, customer, "1 Main Street, Springfield", null);
    }

    private User persistedUser(String email) {
        return userRepository.saveAndFlush(User.registerCustomer(
                email, "test-only-password-hash", "Ada", "Lovelace"));
    }

    private Product persistedProduct(String sku, String name, String slug, String price) {
        Category category = categoryRepository.findBySlug("books")
                .orElseGet(() -> categoryRepository.saveAndFlush(Category.create("Books", "books", null)));
        return productRepository.saveAndFlush(Product.create(
                sku, name, slug, null, new BigDecimal(price), null, 10, category));
    }

    private void insertOrderItem(
            Long orderId, Long productId, String unitPrice, int quantity, String lineTotal) {
        jdbcTemplate.update(
                """
                insert into order_items (
                    order_id, product_id, product_name, sku, unit_price, quantity,
                    line_total, created_at, updated_at
                ) values (?, ?, 'Keyboard', 'KB-001', ?::numeric, ?, ?::numeric, now(), now())
                """,
                orderId,
                productId,
                unitPrice,
                quantity,
                lineTotal);
    }

    @Test
    void orderListIndexesCoverUserStatusAndCreatedAtSorts() {
        List<String> indexes = jdbcTemplate.queryForList(
                """
                select indexname
                from pg_indexes
                where schemaname = current_schema()
                  and tablename = 'orders'
                order by indexname
                """,
                String.class);

        assertThat(indexes)
                .contains(
                        "ix_orders_user_id_created_at_id",
                        "ix_orders_status_created_at_id",
                        "ix_orders_created_at_id");
    }
}
