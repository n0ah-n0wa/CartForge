package com.example.ecommerce.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the authorization boundary the specification requires. Business
 * controllers do not exist yet, so a permitted request ends in 404 — which is
 * itself the signal that security let it through, because a denied one is
 * answered by the filter chain with 401 or 403.
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
class AuthorizationBoundaryIntegrationTest {

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
    void theCatalogIsReadableWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/products/1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/categories/1")).andExpect(status().isNotFound());
    }

    @Test
    void catalogWritesAreRejectedWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/products")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/categories")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerCannotCreateUpdateOrDeleteProducts() throws Exception {
        assertForbiddenForCustomer(post("/api/v1/products"));
        assertForbiddenForCustomer(put("/api/v1/products/1"));
        assertForbiddenForCustomer(patch("/api/v1/products/1"));
        assertForbiddenForCustomer(delete("/api/v1/products/1"));
    }

    @Test
    void aCustomerCannotCreateUpdateOrDeleteCategories() throws Exception {
        assertForbiddenForCustomer(post("/api/v1/categories"));
        assertForbiddenForCustomer(put("/api/v1/categories/1"));
        assertForbiddenForCustomer(patch("/api/v1/categories/1"));
        assertForbiddenForCustomer(delete("/api/v1/categories/1"));
    }

    @Test
    void anAdministratorMayWriteToTheCatalog() throws Exception {
        mockMvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/categories/1").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCustomerCannotChangeOrderStatus() throws Exception {
        mockMvc.perform(patch("/api/v1/orders/1/status")).andExpect(status().isUnauthorized());
        assertForbiddenForCustomer(patch("/api/v1/admin/orders/1/status"));
        assertForbiddenForCustomer(patch("/api/v1/orders/1/status"));
        assertForbiddenForCustomer(put("/api/v1/orders/1/status"));
        assertForbiddenForCustomer(post("/api/v1/orders/1/status"));
    }

    @Test
    void nestedCatalogPathsAreNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/products/1/inventory")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/categories/1/products")).andExpect(status().isUnauthorized());
        assertForbiddenForCustomer(get("/api/v1/products/1/inventory"));
        assertForbiddenForCustomer(patch("/api/v1/products/1/stock"));
    }

    @Test
    void onlyPostOnTheAuthEndpointsIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/auth/register")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/login")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/auth/register")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenInTheQueryStringDoesNotAuthenticate() throws Exception {
        String token = jwtTokenService
                .issue(new AuthenticatedUser(1L, "ada@example.com", UserRole.CUSTOMER))
                .accessToken();

        mockMvc.perform(get("/api/v1/orders").queryParam("access_token", token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/orders").queryParam("access_token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedFailuresDoNotLeakInternalsAndCarrySecureHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Cache-Control"));
    }

    @Test
    void aCustomerCannotReachAnyAdministrativeEndpoint() throws Exception {
        assertForbiddenForCustomer(get("/api/v1/admin/orders"));
        assertForbiddenForCustomer(get("/api/v1/admin/orders/1"));
    }

    @Test
    void anAdministratorMayReachAdministrativeEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/admin/orders/1/status").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cartAndOrderEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/cart")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/cart/items")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAuthenticatedCustomerReachesTheirOwnCartAndOrders() throws Exception {
        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void registrationAndLoginStayPublic() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/auth/login")).andExpect(status().isNotFound());
    }

    private void assertForbiddenForCustomer(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isForbidden());
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
