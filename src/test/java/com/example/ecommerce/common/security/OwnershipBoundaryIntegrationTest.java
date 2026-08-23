package com.example.ecommerce.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Section 93 requires ownership to come from the security context rather than
 * from the request. The probe handlers below exist only in the test sources;
 * they stand in for the customer endpoints that are not written yet.
 */
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
@Import(OwnershipBoundaryIntegrationTest.OwnershipProbeController.class)
class OwnershipBoundaryIntegrationTest {

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

    @Test
    void theActingUserComesFromTheTokenNotFromTheRequest() throws Exception {
        mockMvc.perform(get("/probe/acting-user")
                        .param("userId", "999")
                        .header(HttpHeaders.AUTHORIZATION, tokenFor(7L, UserRole.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(content().string("7"));
    }

    @Test
    void aCustomerReachesAResourceTheyOwn() throws Exception {
        mockMvc.perform(get("/probe/owned/7").header(HttpHeaders.AUTHORIZATION, tokenFor(7L, UserRole.CUSTOMER)))
                .andExpect(status().isOk());
    }

    @Test
    void aCustomerCannotReachAnotherUsersResource() throws Exception {
        mockMvc.perform(get("/probe/owned/8").header(HttpHeaders.AUTHORIZATION, tokenFor(7L, UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorCannotBorrowACustomersOwnPath() throws Exception {
        mockMvc.perform(get("/probe/owned/7").header(HttpHeaders.AUTHORIZATION, tokenFor(2L, UserRole.ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRequireAdminRuleDeniesCustomersAndAllowsAdministrators() throws Exception {
        mockMvc.perform(get("/probe/admin-only").header(HttpHeaders.AUTHORIZATION, tokenFor(7L, UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/probe/admin-only").header(HttpHeaders.AUTHORIZATION, tokenFor(2L, UserRole.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void anUnauthenticatedRequestNeverReachesAnOwnershipCheck() throws Exception {
        mockMvc.perform(get("/probe/owned/7")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/probe/acting-user")).andExpect(status().isUnauthorized());
    }

    private String tokenFor(long userId, UserRole role) {
        return "Bearer " + jwtTokenService
                .issue(new AuthenticatedUser(userId, "user" + userId + "@example.com", role))
                .accessToken();
    }

    @TestConfiguration
    @RestController
    @RequestMapping("/probe")
    static class OwnershipProbeController {

        private final CurrentUserProvider currentUser;

        OwnershipProbeController(CurrentUserProvider currentUser) {
            this.currentUser = currentUser;
        }

        /** Deliberately offered a conflicting {@code userId}, which must be ignored. */
        @GetMapping("/acting-user")
        String actingUser(@RequestParam(name = "userId", required = false) Long clientSuppliedUserId) {
            return String.valueOf(currentUser.requireUserId());
        }

        @GetMapping("/owned/{ownerId}")
        String owned(@PathVariable Long ownerId) {
            currentUser.requireSelf(ownerId);
            return "ok";
        }

        @GetMapping("/admin-only")
        @RequireAdmin
        String adminOnly() {
            return "ok";
        }
    }
}
