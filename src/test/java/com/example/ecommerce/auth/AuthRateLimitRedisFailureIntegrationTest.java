package com.example.ecommerce.auth;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis is unreachable for the whole test. Authentication must remain usable
 * (fail open) even with a very low configured limit.
 */
@SpringBootTest(
        properties = {
            "spring.cache.type=redis",
            "spring.data.redis.timeout=200ms",
            "spring.data.redis.connect-timeout=200ms",
            "app.rate-limit.auth.enabled=true",
            "app.rate-limit.auth.limit=1",
            "app.rate-limit.auth.window-seconds=60"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthRateLimitRedisFailureIntegrationTest {

    private static final String PASSWORD = "test-only-Password123!";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DATABASE_USERNAME", POSTGRES::getUsername);
        registry.add("DATABASE_PASSWORD", POSTGRES::getPassword);
        registry.add("REDIS_URL", () -> "redis://127.0.0.1:1");
        registry.add("JWT_SECRET", () -> "test-only-jwt-secret-not-for-production");
        registry.add("CORS_ORIGINS", () -> "http://localhost");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void authenticationContinuesWhenRedisIsUnavailable() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(APPLICATION_JSON)
                            .content(
                                    """
                                    {"email":"user-%d@example.com","password":"%s","firstName":"Ada","lastName":"Customer"}
                                    """
                                            .formatted(i, PASSWORD)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value("user-" + i + "@example.com"));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(
                                """
                                {"email":"user-0@example.com","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }
}
