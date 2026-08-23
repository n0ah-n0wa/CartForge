package com.example.ecommerce.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.dto.CreateUserCommand;
import com.example.ecommerce.user.dto.UserResponse;
import com.example.ecommerce.user.entity.User;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class UserMapperTest {

    private final UserMapper mapper = new UserMapper();

    @Test
    void mapsCreateCommandToCustomerEntityWithoutExposingPlaintextPassword() {
        CreateUserCommand command = new CreateUserCommand(
                "Ada@Example.com",
                "test-only-password-hash",
                "Ada",
                "Lovelace",
                null);

        User user = mapper.toEntity(command);

        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("test-only-password-hash");
        assertThat(user.getFirstName()).isEqualTo("Ada");
        assertThat(user.getLastName()).isEqualTo("Lovelace");
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.toString()).doesNotContain("test-only-password-hash");
    }

    @Test
    void mapsEntityToResponseWithoutPasswordHash() {
        User user = User.registerCustomer(
                "customer@example.com",
                "test-only-password-hash",
                "Ada",
                "Lovelace");

        UserResponse response = mapper.toResponse(user);

        assertThat(response.email()).isEqualTo("customer@example.com");
        assertThat(response.firstName()).isEqualTo("Ada");
        assertThat(response.lastName()).isEqualTo("Lovelace");
        assertThat(response.role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(response.enabled()).isTrue();
        assertThat(Arrays.stream(UserResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .doesNotContain("passwordHash", "password");
    }
}
