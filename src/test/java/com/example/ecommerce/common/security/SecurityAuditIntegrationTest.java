package com.example.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.service.JwtTokenService;
import com.example.ecommerce.cart.entity.Cart;
import com.example.ecommerce.cart.repository.CartRepository;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.common.support.IntegrationTestContainers;
import com.example.ecommerce.order.entity.Order;
import com.example.ecommerce.order.repository.OrderItemRepository;
import com.example.ecommerce.order.repository.OrderRepository;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dedicated security regression checklist: unauthorized access, ownership IDOR,
 * privilege escalation, JWT abuse, password/error disclosure, actuator surface,
 * and CORS misconfiguration.
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
class SecurityAuditIntegrationTest {

    private static final String PASSWORD = "test-only-Password123!";
    private static final String ALLOWED_ORIGIN = "https://shop.example.com";
    private static final String JWT_SECRET = "test-only-jwt-secret-not-for-production";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = IntegrationTestContainers.postgres();

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        IntegrationTestContainers.registerPostgresWithoutRedis(registry, POSTGRES);
        registry.add("CORS_ORIGINS", () -> ALLOWED_ORIGIN);
        registry.add("JWT_SECRET", () -> JWT_SECRET);
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
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    private User alice;
    private User bob;
    private Product keyboard;
    private Long aliceOrderId;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        cartRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        Category books = categoryRepository.saveAndFlush(Category.create("Books", "books", null));
        alice = userRepository.saveAndFlush(
                User.registerCustomer("alice-sec@example.com", "test-only-password-hash", "Alice", "Customer"));
        bob = userRepository.saveAndFlush(
                User.registerCustomer("bob-sec@example.com", "test-only-password-hash", "Bob", "Customer"));
        keyboard = productRepository.saveAndFlush(Product.create(
                "KB-SEC",
                "Keyboard",
                "keyboard-sec",
                null,
                new BigDecimal("49.50"),
                CurrencyCode.EUR,
                10,
                books));

        Cart cart = Cart.forUser(alice);
        cart.addOrIncrease(keyboard, 1);
        cartRepository.saveAndFlush(cart);

        Order order = Order.place("ORD-2026-007777", alice, "1 Security Lane", CurrencyCode.EUR);
        order.addItem(keyboard, 1);
        aliceOrderId = orderRepository.saveAndFlush(order).getId();
    }

    @Test
    void protectedResourcesRejectUnauthenticatedCallers() throws Exception {
        mockMvc.perform(get("/api/v1/cart")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/products").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotEscalateToAdminOrCatalogWrites() throws Exception {
        String customer = bearer(alice, UserRole.CUSTOMER);

        mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/products").param("active", "false").header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void registrationIgnoresClientSuppliedAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"escalate@example.com","password":"%s","firstName":"Eve","lastName":"User","role":"ADMIN"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User created = userRepository.findByEmailIgnoreCase("escalate@example.com").orElseThrow();
        assertThat(created.getRole()).isEqualTo(UserRole.CUSTOMER);

        mockMvc.perform(get("/api/v1/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(created, UserRole.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void bobCannotReadOrCancelAlicesOrder() throws Exception {
        String bobToken = bearer(bob, UserRole.CUSTOMER);

        mockMvc.perform(get("/api/v1/orders/" + aliceOrderId).header(HttpHeaders.AUTHORIZATION, bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/orders/" + aliceOrderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        assertThat(orderRepository.findById(aliceOrderId).orElseThrow().getStatus().name())
                .isEqualTo("PENDING");
    }

    @Test
    void bobCannotMutateAlicesCartLine() throws Exception {
        String bobToken = bearer(bob, UserRole.CUSTOMER);

        mockMvc.perform(delete("/api/v1/cart/items/{productId}", keyboard.getId())
                        .header(HttpHeaders.AUTHORIZATION, bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));

        Cart aliceCart = cartRepository.findWithItemsByUserId(alice.getId()).orElseThrow();
        assertThat(aliceCart.getItems()).hasSize(1);
    }

    @Test
    void validationErrorsDoNotEchoSubmittedPasswords() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"%s","firstName":"Ada","lastName":"Lovelace"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD);
        assertThat(body).doesNotContain("{bcrypt}");
    }

    @Test
    void loginFailuresDoNotDiscloseWhetherAnAccountExists() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"known@example.com","password":"%s","firstName":"Ada","lastName":"Lovelace"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated());

        String unknown = loginFailureBody("missing@example.com", PASSWORD);
        String wrong = loginFailureBody("known@example.com", "wrong-Password123!");
        assertThat(unknown).contains("INVALID_CREDENTIALS");
        assertThat(wrong).contains("INVALID_CREDENTIALS");
        assertThat(unknown).doesNotContain(PASSWORD);
        assertThat(wrong).doesNotContain("wrong-Password123!");
        assertThat(unknown).doesNotContain("missing@example.com");
    }

    @Test
    void malformedAndTamperedJwtsAreRejectedWithoutLeakingTokenMaterial() throws Exception {
        String valid = jwtTokenService
                .issue(new AuthenticatedUser(alice.getId(), alice.getEmail(), UserRole.CUSTOMER))
                .accessToken();
        String[] parts = valid.split("\\.");
        String truncated = parts[0] + "." + parts[1];
        // Flip a middle signature character (not the last) so Base64 padding quirks cannot
        // accidentally leave a still-verifiable MAC.
        String bitFlipped = parts[0] + "." + parts[1] + "." + flipCharacter(parts[2], parts[2].length() / 2);
        String replacedSignature = parts[0] + "." + parts[1] + "." + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String garbage = "not.a.jwt";

        assertThat(bitFlipped).isNotEqualTo(valid);

        for (String token : new String[] {truncated, bitFlipped, replacedSignature, garbage, "a.b.c"}) {
            SecurityContextHolder.clearContext();
            MvcResult result = mockMvc.perform(get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            assertThat(body).as("body for token %s", token).doesNotContain(token);
            assertThat(body).doesNotContain(valid);
            assertThat(body).doesNotContain("stackTrace");
        }
    }

    @Test
    void expiredAndForeignSignedJwtsAreRejected() throws Exception {
        Instant issuedAt = Instant.now().minusSeconds(7_200);
        String expired = sign(jwtEncoder, alice.getId(), "CUSTOMER", issuedAt, issuedAt.plusSeconds(60));

        mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "a-different-secret-that-is-long-enough".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        String forged = sign(foreignEncoder, alice.getId(), "ADMIN", Instant.now(), Instant.now().plusSeconds(600));

        mockMvc.perform(get("/api/v1/admin/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dangerousActuatorPathsAreDeniedAndPrometheusStaysPublicWithoutSecrets() throws Exception {
        // Anonymous denyAll → 401 via the authentication entry point.
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/heapdump")).andExpect(status().isUnauthorized());

        // Authenticated callers are still blocked (denyAll → 403 access denied).
        String customer = bearer(alice, UserRole.CUSTOMER);
        String admin = bearer(alice, UserRole.ADMIN);
        mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/heapdump").header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/heapdump").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isForbidden());

        MvcResult health = mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andReturn();
        assertThat(health.getResponse().getContentAsString()).doesNotContain("DATABASE_PASSWORD");
        assertThat(health.getResponse().getContentAsString()).doesNotContain("JWT_SECRET");

        MvcResult prometheus =
                mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk()).andReturn();
        String metrics = prometheus.getResponse().getContentAsString();
        assertThat(metrics).doesNotContain("DATABASE_PASSWORD");
        assertThat(metrics).doesNotContain("JWT_SECRET");
        assertThat(metrics).doesNotContain("test-only-password-hash");
    }

    @Test
    void corsDoesNotAllowEvilOriginsOrCredentialsAndDoesNotBypassAuth() throws Exception {
        mockMvc.perform(options("/api/v1/orders")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        mockMvc.perform(options("/api/v1/orders")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));

        mockMvc.perform(get("/api/v1/orders").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/prometheus").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void swaggerIsNotPublicOutsideDev() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    private String loginFailureBody(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String bearer(User user, UserRole role) {
        return "Bearer "
                + jwtTokenService.issue(new AuthenticatedUser(user.getId(), user.getEmail(), role)).accessToken();
    }

    private static String sign(
            JwtEncoder encoder, Long subject, String role, Instant issuedAt, Instant expiresAt) {
        return encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .subject(String.valueOf(subject))
                                .claim(JwtClaims.EMAIL, "alice-sec@example.com")
                                .claim(JwtClaims.ROLE, role)
                                .issuedAt(issuedAt)
                                .expiresAt(expiresAt)
                                .build()))
                .getTokenValue();
    }

    private static String flipCharacter(String value, int index) {
        char current = value.charAt(index);
        char flipped = current == 'A' ? 'B' : 'A';
        return value.substring(0, index) + flipped + value.substring(index + 1);
    }
}
