package com.example.ecommerce.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.common.security.JwtClaims;
import com.example.ecommerce.common.support.PersistedAuthUsers;
import com.example.ecommerce.user.repository.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises Bearer authentication through the real filter chain. No controllers
 * exist yet, so an accepted request ends in 404: that still proves the token was
 * authenticated and authorised, because a rejected one never reaches the
 * dispatcher.
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
class JwtAuthenticationIntegrationTest {

    private static final String PROTECTED_PATH = "/api/v1/orders";
    private static final String ADMIN_PATH = "/api/v1/admin/orders";

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
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private PersistedAuthUsers authUsers;

    @BeforeEach
    void setUpAuthUsers() {
        authUsers = new PersistedAuthUsers(userRepository, passwordEncoder, jwtTokenService);
    }

    @Test
    void rejectsARequestWithoutAToken() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsAValidToken() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, bearer(customerToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void rejectsAMalformedToken() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, bearer("a.b.c")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATokenSignedWithAnotherSecret() throws Exception {
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "a-different-secret-that-is-long-enough".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

        String forged = sign(foreignEncoder, "CUSTOMER", Instant.now(), Instant.now().plusSeconds(600));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, bearer(forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        Instant issuedAt = Instant.now().minusSeconds(7_200);
        String expired = sign(jwtEncoder, "CUSTOMER", issuedAt, issuedAt.plusSeconds(60));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, bearer(expired)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deniesAdministrativePathsToACustomerToken() throws Exception {
        mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(customerToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsAdministrativePathsToAnAdministratorToken() throws Exception {
        mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void deniesAdministrativePathsWithoutAToken() throws Exception {
        mockMvc.perform(get(ADMIN_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void leavesThePublicCatalogAndAuthEndpointsOpen() throws Exception {
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isOk());
    }

    private String customerToken() {
        return authUsers.accessToken(authUsers.ensureCustomer());
    }

    private String adminToken() {
        return authUsers.accessToken(authUsers.ensureAdmin());
    }

    private String sign(JwtEncoder encoder, String role, Instant issuedAt, Instant expiresAt) {
        String subject = String.valueOf(authUsers.ensureCustomer().getId());
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .subject(subject)
                                .claim(JwtClaims.EMAIL, "ada@example.com")
                                .claim(JwtClaims.ROLE, role)
                                .issuedAt(issuedAt)
                                .expiresAt(expiresAt)
                                .build()))
                .getTokenValue();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
