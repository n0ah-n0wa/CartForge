package com.example.ecommerce.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * Proves correlation ID propagation and that access/auth logs never contain
 * passwords, JWTs, or Authorization header values.
 */
@SpringBootTest(
        properties = {
            "spring.cache.type=simple",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "app.rate-limit.auth.enabled=false"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CorrelationIdIntegrationTest {

    private static final Pattern JWT_LIKE = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final String PASSWORD = "test-only-Password123!";
    private static final String BEARER_TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature-segment-for-test";

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

    private ListAppender<ILoggingEvent> requestLogs;
    private ListAppender<ILoggingEvent> authLogs;
    private ListAppender<ILoggingEvent> securityLogs;

    @BeforeEach
    void attachAppenders() {
        requestLogs = attach(RequestLoggingFilter.class);
        authLogs = attach(com.example.ecommerce.auth.service.AuthenticationService.class);
        securityLogs = attach(com.example.ecommerce.common.security.SecurityProblemHandlers.class);
    }

    @AfterEach
    void detachAppenders() {
        detach(RequestLoggingFilter.class, requestLogs);
        detach(com.example.ecommerce.auth.service.AuthenticationService.class, authLogs);
        detach(com.example.ecommerce.common.security.SecurityProblemHandlers.class, securityLogs);
    }

    @Test
    void propagatesClientCorrelationIdOnSuccessAndErrors() throws Exception {
        String correlationId = "client-corr-" + UUID.randomUUID();

        mockMvc.perform(get("/api/v1/products").header(CorrelationIds.HEADER, correlationId))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIds.HEADER, correlationId));

        MvcResult error = mockMvc.perform(get("/api/v1/orders")
                        .header(CorrelationIds.HEADER, correlationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + BEARER_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelationIds.HEADER, correlationId))
                .andExpect(jsonPath("$.correlationId").value(correlationId))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn();

        assertSafe(error.getResponse().getContentAsString());
        assertLogsSafe(requestLogs.list);
        assertLogsSafe(securityLogs.list);
        assertThat(requestLogs.list)
                .anySatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry(CorrelationIds.MDC_KEY, correlationId));
    }

    @Test
    void generatesCorrelationIdWhenHeaderAbsent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIds.HEADER))
                .andReturn();

        String generated = result.getResponse().getHeader(CorrelationIds.HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
    }

    @Test
    void replacesUnsafeCorrelationId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/categories")
                        .header(CorrelationIds.HEADER, "bad id / Authorization: Bearer " + BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIds.HEADER))
                .andReturn();

        String resolved = result.getResponse().getHeader(CorrelationIds.HEADER);
        assertThat(resolved).doesNotContain(" ");
        assertThat(resolved).doesNotContain("Bearer");
        assertThat(resolved).doesNotContain(BEARER_TOKEN);
    }

    @Test
    void authenticationFailureLogsDoNotContainPasswordOrToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(CorrelationIds.HEADER, "login-corr-1")
                        .content(
                                """
                                {"email":"nobody@example.com","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(CorrelationIds.HEADER, "login-corr-1"))
                .andExpect(jsonPath("$.correlationId").value("login-corr-1"));

        assertThat(authLogs.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("event=authentication_failed")
                        .contains("nobody@example.com"));
        assertLogsSafe(authLogs.list);
        assertLogsSafe(requestLogs.list);
    }

    private static ListAppender<ILoggingEvent> attach(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        if (appender == null) {
            return;
        }
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        logger.detachAppender(appender);
        appender.stop();
    }

    private static void assertLogsSafe(List<ILoggingEvent> events) {
        for (ILoggingEvent event : events) {
            assertSafe(event.getFormattedMessage());
            assertThat(event.getArgumentArray() == null ? new Object[0] : event.getArgumentArray())
                    .allSatisfy(arg -> {
                        if (arg != null) {
                            assertSafe(String.valueOf(arg));
                        }
                    });
        }
    }

    private static void assertSafe(String text) {
        assertThat(text).doesNotContain(PASSWORD);
        assertThat(text).doesNotContain(BEARER_TOKEN);
        assertThat(text).doesNotContain("Authorization");
        assertThat(JWT_LIKE.matcher(text).find()).isFalse();
    }
}
