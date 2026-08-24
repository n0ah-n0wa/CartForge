package com.example.ecommerce.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class RequestLoggingFilterTest {

    private static final String SECRET_PASSWORD = "SuperSecretPassword123!";
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.test-signature";

    private ListAppender<ILoggingEvent> appender;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new CorrelationIdFilter(), new RequestLoggingFilter())
                .build();
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void accessLogOmitsAuthorizationQueryAndBodySecrets() throws Exception {
        mockMvc.perform(post("/probe/echo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JWT)
                        .header(CorrelationIds.HEADER, "access-log-corr")
                        .queryParam("password", SECRET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + SECRET_PASSWORD + "\",\"accessToken\":\"" + JWT + "\"}"))
                .andExpect(status().isOk());

        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertThat(message).contains("event=http_request");
            assertThat(message).contains("method=POST");
            assertThat(message).contains("path=/probe/echo");
            assertThat(message).doesNotContain(SECRET_PASSWORD);
            assertThat(message).doesNotContain(JWT);
            assertThat(message).doesNotContain("Authorization");
            assertThat(message).doesNotContain("Bearer");
            assertThat(message).doesNotContain("query");
            assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationIds.MDC_KEY, "access-log-corr");
        }
    }

    @RestController
    static class ProbeController {
        @PostMapping("/probe/echo")
        void echo(@RequestBody String ignored) {
            // body is accepted so the test can prove it is not logged
        }
    }
}
