package com.example.ecommerce.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.user.UserRole;
import org.junit.jupiter.api.Test;

class UserTest {

    @Test
    void createNormalizesEmailAndDefaultsRole() {
        User user = User.create("  Ada@Example.COM ", "{bcrypt}hash", " Ada ", " Lovelace ", null);

        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(user.getFirstName()).isEqualTo("Ada");
        assertThat(user.getLastName()).isEqualTo("Lovelace");
        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void createRejectsBlankIdentityFields() {
        assertThatThrownBy(() -> User.create(" ", "{bcrypt}hash", "Ada", "Lovelace", UserRole.CUSTOMER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.create("ada@example.com", " ", "Ada", "Lovelace", UserRole.CUSTOMER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.create("ada@example.com", "{bcrypt}hash", "", "Lovelace", UserRole.CUSTOMER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> User.create("ada@example.com", "{bcrypt}hash", "Ada", null, UserRole.CUSTOMER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disableAndEnableToggleTheFlag() {
        User user = User.registerCustomer("ada@example.com", "{bcrypt}hash", "Ada", "Lovelace");
        user.disable();
        assertThat(user.isEnabled()).isFalse();
        user.enable();
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void replacePasswordHashRejectsBlankAndToStringOmitsTheHash() {
        User user = User.registerCustomer("ada@example.com", "{bcrypt}old", "Ada", "Lovelace");
        user.replacePasswordHash("{bcrypt}new");
        assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}new");
        assertThatThrownBy(() -> user.replacePasswordHash("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(user.toString()).doesNotContain("{bcrypt}");
        assertThat(user.toString()).contains("ada@example.com");
    }
}
