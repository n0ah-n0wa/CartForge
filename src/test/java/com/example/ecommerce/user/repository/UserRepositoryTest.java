package com.example.ecommerce.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.common.persistence.PersistenceConventions;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.entity.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
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
class UserRepositoryTest {

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
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesGeneratedIdentityTimestampsAndDefaultCustomerState() {
        Instant beforePersist = Instant.now().minusSeconds(1);

        User saved = userRepository.saveAndFlush(customer("ada@example.com"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getCreatedAt()).isAfter(beforePersist);
        assertThat(saved.getUpdatedAt()).isAfterOrEqualTo(saved.getCreatedAt());
        assertThat(saved.getPasswordHash()).isEqualTo("test-only-password-hash");
    }

    @Test
    void findsExistingUserByEmailIgnoringCase() {
        userRepository.saveAndFlush(customer("Ada@Example.com"));

        assertThat(userRepository.findByEmailIgnoreCase("ADA@EXAMPLE.COM"))
                .isPresent()
                .get()
                .extracting(User::getEmail)
                .isEqualTo("ada@example.com");
        assertThat(userRepository.existsByEmailIgnoreCase("ada@EXAMPLE.com")).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase("missing@example.com")).isFalse();
    }

    @Test
    void findByIdForUpdateReturnsTheRowOrEmpty() {
        User saved = userRepository.saveAndFlush(customer("lock-lookup@example.com"));

        assertThat(userRepository.findByIdForUpdate(saved.getId()))
                .isPresent()
                .get()
                .extracting(User::getEmail)
                .isEqualTo("lock-lookup@example.com");
        assertThat(userRepository.findByIdForUpdate(9_999L)).isEmpty();
    }

    @Test
    void rejectsDuplicateEmailRegardlessOfCase() {
        userRepository.saveAndFlush(customer("ada@example.com"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(customer("ADA@EXAMPLE.COM")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void incrementsVersionOnUpdateAndRejectsStaleWrites() {
        User saved = userRepository.saveAndFlush(customer("lock@example.com"));
        entityManager.detach(saved);

        User current = userRepository.findById(saved.getId()).orElseThrow();
        current.rename("Augusta", "Lovelace");
        userRepository.saveAndFlush(current);
        assertThat(current.getVersion()).isEqualTo(1L);
        assertThat(current.getUpdatedAt()).isAfterOrEqualTo(current.getCreatedAt());

        saved.rename("Stale", "Write");
        assertThatThrownBy(() -> userRepository.saveAndFlush(saved))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    void databaseRejectsUnknownRole() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                        """
                        insert into users (
                            email, password_hash, first_name, last_name, role, enabled,
                            created_at, updated_at, version
                        ) values (?, ?, ?, ?, ?, true, now(), now(), 0)
                        """,
                        "role@example.com",
                        "test-only-password-hash",
                        "Ada",
                        "Lovelace",
                        "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseDefinesCaseInsensitiveEmailIndexAndRoleCheck() {
        List<String> indexes = jdbcTemplate.queryForList(
                "select indexname from pg_indexes where tablename = 'users'",
                String.class);
        List<String> constraints = jdbcTemplate.queryForList(
                "select conname from pg_constraint where conrelid = 'users'::regclass",
                String.class);

        assertThat(indexes).contains(PersistenceConventions.uniqueConstraintName("users", "email_lower"));
        assertThat(constraints).contains(
                PersistenceConventions.primaryKeyName("users"),
                PersistenceConventions.checkConstraintName("users", "role"));
    }

    private static User customer(String email) {
        return User.registerCustomer(email, "test-only-password-hash", "Ada", "Lovelace");
    }
}
