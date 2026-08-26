package com.example.ecommerce.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.EcommerceApplication;
import com.example.ecommerce.user.repository.UserRepository;
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

@SpringBootTest(classes = EcommerceApplication.class)
@ActiveProfiles("dev")
@Testcontainers
class DevelopmentDataSeederIntegrationTest {

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
        registry.add("app.seed.enabled", () -> "true");
    }

    @Autowired
    private UserRepository userRepository;

    @Test
    void seedsAdminAndCustomerWhenEnabledOnDevProfile() {
        assertThat(userRepository.findByEmailIgnoreCase(DevelopmentDataSeeder.ADMIN_EMAIL)).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase(DevelopmentDataSeeder.CUSTOMER_EMAIL)).isPresent();
    }
}
