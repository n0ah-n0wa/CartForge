package com.example.ecommerce.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.common.support.IntegrationTestContainers;
import com.example.ecommerce.user.repository.UserRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Auth rate limits against real Redis. Window and limit are tightened for the suite.
 */
@SpringBootTest(
        properties = {
            "spring.cache.type=redis",
            "app.rate-limit.auth.enabled=true",
            "app.rate-limit.auth.limit=3",
            "app.rate-limit.auth.window-seconds=60"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthRateLimitIntegrationTest {

    private static final String PASSWORD = "test-only-Password123!";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = IntegrationTestContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationTestContainers.redis();

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.registerPostgres(registry, POSTGRES);
        IntegrationTestContainers.registerRedis(registry, REDIS);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void allowsRequestsWithinTheLimitAndBlocksTheRest() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content(loginBody("nobody@example.com")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("nobody@example.com")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("Too many requests. Try again later."))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        PASSWORD))))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "nobody@example.com"))));
    }

    @Test
    void allowsRequestsAgainAfterWindowCountersExpire() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(APPLICATION_JSON)
                            .content(registerBody("burst-" + i + "@example.com")))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registerBody("blocked@example.com")))
                .andExpect(status().isTooManyRequests());

        // Counter reset simulates window expiry for the HTTP path; real Redis TTL
        // advancement is asserted in RedisFixedWindowAuthRateLimiterTest.
        clearAuthRateKeys();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registerBody("after-reset@example.com")))
                .andExpect(status().isCreated());

        assertThat(userRepository.findByEmailIgnoreCase("after-reset@example.com")).isPresent();
    }

    @Test
    void exhaustingLoginDoesNotConsumeTheRegisterBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content(loginBody("nobody@example.com")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("nobody@example.com")))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registerBody("still-open@example.com")))
                .andExpect(status().isCreated());
    }

    @Test
    void forwardedForDoesNotCreateASeparateLoginBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(APPLICATION_JSON)
                            .content(loginBody("nobody@example.com")))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("nobody@example.com")))
                .andExpect(status().isTooManyRequests());
    }

    private void clearAuthRateKeys() {
        Set<String> keys = redisTemplate.keys("auth-rate:*");
        assertThat(keys).isNotEmpty();
        redisTemplate.delete(keys);
        Set<String> remaining = redisTemplate.keys("auth-rate:*");
        assertThat(remaining == null || remaining.isEmpty()).isTrue();
    }

    private static String loginBody(String email) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD);
    }

    private static String registerBody(String email) {
        return """
                {"email":"%s","password":"%s","firstName":"Ada","lastName":"Customer"}
                """.formatted(email, PASSWORD);
    }
}
