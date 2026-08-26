package com.example.ecommerce.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * HTTP contracts for register/login, including anti-enumeration and that
 * disabling an account rejects both new logins and existing Bearer tokens.
 */
@SpringBootTest(
        properties = {
            "spring.cache.type=simple",
            "app.rate-limit.auth.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthControllerApiIntegrationTest {

    private static final String PASSWORD = "test-only-Password123!";

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
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    void registerCreatesCustomerAndLoginIssuesAUsableToken() throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registerBody("Ada@Example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();

        assertThat(registered.getResponse().getContentAsString()).doesNotContain(PASSWORD);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"role-ignore@example.com","password":"%s","firstName":"Ada","lastName":"Lovelace","role":"ADMIN"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("ADA@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn();

        String token = new ObjectMapper()
                .findAndRegisterModules()
                .readTree(login.getResponse().getContentAsString())
                .get("accessToken")
                .asText();
        assertThat(login.getResponse().getContentAsString()).doesNotContain(PASSWORD);

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void loginFailuresAreIndistinguishableOverHttp() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registerBody("ada@example.com")))
                .andExpect(status().isCreated());

        User disabled = User.registerCustomer(
                "off@example.com", passwordEncoder.encode(PASSWORD), "Off", "User");
        disabled.disable();
        userRepository.saveAndFlush(disabled);

        String unknown = loginFailure("nobody@example.com", PASSWORD);
        String wrong = loginFailure("ada@example.com", "wrong-Password123!");
        String disabledAccount = loginFailure("off@example.com", PASSWORD);

        assertThat(unknown).contains("INVALID_CREDENTIALS");
        assertThat(unknown).contains("Invalid email or password");
        assertThat(wrong).contains("INVALID_CREDENTIALS");
        assertThat(disabledAccount).contains("INVALID_CREDENTIALS");
        assertThat(unknown).doesNotContain(PASSWORD);
        assertThat(unknown).doesNotContain("nobody@example.com");
        assertThat(wrong).doesNotContain("wrong-Password123!");
        assertThat(disabledAccount).doesNotContain(PASSWORD);
    }

    @Test
    void malformedLoginEmailIsInvalidCredentialsNotValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("not-an-email")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void disablingAnAccountBlocksLoginAndRevokesExistingBearerAccess() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content(registerBody("keep-token@example.com")))
                .andExpect(status().isCreated());

        User user = userRepository.findByEmailIgnoreCase("keep-token@example.com").orElseThrow();
        String token = jwtTokenService
                .issue(new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole()))
                .accessToken();

        user.disable();
        userRepository.saveAndFlush(user);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content(loginBody("keep-token@example.com")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void demotingAnAdminRevokesAdminAccessBeforeTokenExpiry() throws Exception {
        User admin = User.create(
                "demoted-admin@example.com",
                passwordEncoder.encode(PASSWORD),
                "Root",
                "Admin",
                UserRole.ADMIN);
        userRepository.saveAndFlush(admin);

        String adminToken = jwtTokenService
                .issue(new AuthenticatedUser(admin.getId(), admin.getEmail(), UserRole.ADMIN))
                .accessToken();

        mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        admin.assignRole(UserRole.CUSTOMER);
        userRepository.saveAndFlush(admin);

        mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private String loginFailure(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private static String registerBody(String email) {
        return """
                {"email":"%s","password":"%s","firstName":"Ada","lastName":"Lovelace"}
                """.formatted(email, PASSWORD);
    }

    private static String loginBody(String email) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD);
    }
}
