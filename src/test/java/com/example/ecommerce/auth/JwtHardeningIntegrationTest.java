package com.example.ecommerce.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.common.security.JwtClaims;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Attempts realistic ways to get past Bearer authentication with a token this
 * service would never issue.
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
class JwtHardeningIntegrationTest {

    private static final String PROTECTED_PATH = "/api/v1/orders";
    private static final String ADMIN_PATH = "/api/v1/admin/orders";
    private static final String SECRET = "test-only-jwt-secret-not-for-production";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void registerInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DATABASE_USERNAME", POSTGRES::getUsername);
        registry.add("DATABASE_PASSWORD", POSTGRES::getPassword);
        registry.add("REDIS_URL", () -> "redis://localhost:6379");
        registry.add("JWT_SECRET", () -> SECRET);
        registry.add("CORS_ORIGINS", () -> "http://localhost");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void rejectsATokenThatNeverExpires() throws Exception {
        String everlasting = sign(JwtClaimsSet.builder()
                .subject("1")
                .claim(JwtClaims.EMAIL, "ada@example.com")
                .claim(JwtClaims.ROLE, "CUSTOMER")
                .issuedAt(Instant.now()));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + everlasting))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnAdministratorTokenWithNoSubject() throws Exception {
        String subjectless = sign(JwtClaimsSet.builder()
                .claim(JwtClaims.EMAIL, "root@example.com")
                .claim(JwtClaims.ROLE, "ADMIN")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)));

        mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + subjectless))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATokenCarryingNoRole() throws Exception {
        String roleless = sign(JwtClaimsSet.builder()
                .subject("1")
                .claim(JwtClaims.EMAIL, "ada@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + roleless))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Proves {@link #handSign} produces a token this service really accepts, so a
     * rejection in the tests above is caused by the missing claim and not by a
     * broken helper.
     */
    @Test
    void acceptsAHandSignedTokenThatCarriesEveryRequiredClaim() throws Exception {
        Instant now = Instant.now();
        String complete = handSign("{\"sub\":\"1\",\"email\":\"ada@example.com\",\"role\":\"CUSTOMER\",\"iat\":"
                + now.getEpochSecond() + ",\"exp\":" + now.plusSeconds(600).getEpochSecond() + "}");

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + complete))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsATokenWithNoEmail() throws Exception {
        String anonymousClaims = sign(JwtClaimsSet.builder()
                .subject("1")
                .claim(JwtClaims.ROLE, "CUSTOMER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + anonymousClaims))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsABlankRoleClaim() throws Exception {
        String blankRole = sign(JwtClaimsSet.builder()
                .subject("1")
                .claim(JwtClaims.EMAIL, "ada@example.com")
                .claim(JwtClaims.ROLE, "   ")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + blankRole))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stillAcceptsATokenCarryingEveryRequiredClaim() throws Exception {
        String complete = sign(JwtClaimsSet.builder()
                .subject("1")
                .claim(JwtClaims.EMAIL, "ada@example.com")
                .claim(JwtClaims.ROLE, "CUSTOMER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + complete))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAnUnsignedNoneAlgorithmToken() throws Exception {
        String header = base64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64("{\"sub\":\"1\",\"role\":\"ADMIN\",\"exp\":"
                + Instant.now().plusSeconds(600).getEpochSecond() + "}");

        mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + header + "." + payload + "."))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsARoleClaimThatAlreadyLooksLikeAnAuthority() throws Exception {
        String crafted = sign(JwtClaimsSet.builder()
                .subject("1")
                .claim(JwtClaims.EMAIL, "ada@example.com")
                .claim(JwtClaims.ROLE, "ROLE_ADMIN")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)));

        mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + crafted))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsALowercasedRoleClaim() throws Exception {
        String crafted = sign(JwtClaimsSet.builder()
                .subject("1")
                .claim(JwtClaims.EMAIL, "ada@example.com")
                .claim(JwtClaims.ROLE, "admin")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)));

        mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + crafted))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnInventedRoleClaim() throws Exception {
        String crafted = sign(JwtClaimsSet.builder()
                .subject("1")
                .claim(JwtClaims.EMAIL, "ada@example.com")
                .claim(JwtClaims.ROLE, "SUPERUSER")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + crafted))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATokenSignedWithADifferentMacAlgorithm() throws Exception {
        Instant now = Instant.now();
        String payload = "{\"sub\":\"1\",\"email\":\"ada@example.com\",\"role\":\"ADMIN\",\"iat\":"
                + now.getEpochSecond() + ",\"exp\":" + now.plusSeconds(600).getEpochSecond() + "}";

        mockMvc.perform(get(ADMIN_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + handSign("HS384", "HmacSHA384", payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATokenThatDeclaresAnAsymmetricAlgorithm() throws Exception {
        Instant now = Instant.now();
        String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64("{\"sub\":\"1\",\"email\":\"root@example.com\",\"role\":\"ADMIN\",\"iat\":"
                + now.getEpochSecond() + ",\"exp\":" + now.plusSeconds(600).getEpochSecond() + "}");

        mockMvc.perform(get(ADMIN_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + header + "." + payload + ".fakesig"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticationFailuresDoNotLeakJwtInternals() throws Exception {
        Instant issuedAt = Instant.now().minusSeconds(7_200);
        String expired = sign(JwtClaimsSet.builder()
                .subject("1")
                .claim(JwtClaims.EMAIL, "ada@example.com")
                .claim(JwtClaims.ROLE, "CUSTOMER")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(60)));

        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().string(""));
    }

    @Test
    void rejectsANonBearerAuthorizationScheme() throws Exception {
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Basic YWRhOnNlY3JldA=="))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized());
    }

    private String sign(JwtClaimsSet.Builder claims) {
        return jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    /**
     * The encoder fills in {@code iat} when it is absent, so a token missing that
     * claim has to be assembled and signed by hand.
     */
    private static String handSign(String payloadJson) throws GeneralSecurityException {
        return handSign("HS256", "HmacSHA256", payloadJson);
    }

    private static String handSign(String jwtAlgorithm, String macAlgorithm, String payloadJson)
            throws GeneralSecurityException {
        String signingInput = base64("{\"alg\":\"" + jwtAlgorithm + "\",\"typ\":\"JWT\"}")
                + "." + base64(payloadJson);
        Mac mac = Mac.getInstance(macAlgorithm);
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), macAlgorithm));
        byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
