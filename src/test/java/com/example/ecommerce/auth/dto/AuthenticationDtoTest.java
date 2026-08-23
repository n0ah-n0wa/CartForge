package com.example.ecommerce.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthenticationDtoTest {

    private static final String PLAINTEXT = "test-only-Password123!";

    @Test
    void registrationRequestNeverPrintsThePassword() {
        String printed = new RegistrationRequest("ada@example.com", PLAINTEXT, "Ada", "Lovelace").toString();

        assertThat(printed).doesNotContain(PLAINTEXT).contains("****");
    }

    @Test
    void loginRequestNeverPrintsThePassword() {
        String printed = new LoginRequest("ada@example.com", PLAINTEXT).toString();

        assertThat(printed).doesNotContain(PLAINTEXT).contains("****");
    }

    @Test
    void registrationCannotRequestARole() {
        assertThat(componentNames(RegistrationRequest.class))
                .as("registration must always create a CUSTOMER")
                .doesNotContain("role");
    }

    @Test
    void aRoleFieldInRegistrationJsonCannotBeBound() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        RegistrationRequest request = mapper.readValue(
                """
                {"email":"ada@example.com","password":"SecurePass123!","firstName":"Ada","lastName":"Lovelace","role":"ADMIN"}
                """,
                RegistrationRequest.class);

        assertThat(request.email()).isEqualTo("ada@example.com");
        assertThat(componentNames(RegistrationRequest.class)).doesNotContain("role");
    }

    @Test
    void accessTokenResponseNeverPrintsTheToken() {
        String token = "eyJhbGciOiJIUzI1NiJ9.e30.signature";
        String printed = new AccessTokenResponse(token, AccessTokenResponse.BEARER, Instant.EPOCH)
                .toString();

        assertThat(printed).doesNotContain(token).contains("****");
    }

    @Test
    void theAuthenticatedPrincipalCarriesNoSecret() {
        assertThat(componentNames(AuthenticatedUser.class))
                .containsExactlyInAnyOrder("userId", "email", "role");
    }

    private static List<String> componentNames(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
