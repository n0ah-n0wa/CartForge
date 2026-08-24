package com.example.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DatabaseUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private DatabaseUserDetailsService userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new DatabaseUserDetailsService(userRepository);
    }

    @Test
    void mapsAnEnabledCustomer() {
        User user = User.registerCustomer(
                "ada@example.com", "{bcrypt}hash", "Ada", "Lovelace");
        ReflectionTestUtils.setField(user, "id", 11L);
        when(userRepository.findByEmailIgnoreCase("ADA@example.com")).thenReturn(Optional.of(user));

        UserDetails details = userDetailsService.loadUserByUsername("ADA@example.com");

        assertThat(details.getUsername()).isEqualTo("ada@example.com");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getPassword()).isEqualTo("{bcrypt}hash");
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
        verify(userRepository).findByEmailIgnoreCase("ADA@example.com");
    }

    @Test
    void mapsADisabledAdministrator() {
        User admin = User.create(
                "root@example.com", "{bcrypt}hash", "Root", "Admin", UserRole.ADMIN);
        admin.disable();
        when(userRepository.findByEmailIgnoreCase("root@example.com")).thenReturn(Optional.of(admin));

        UserDetails details = userDetailsService.loadUserByUsername("root@example.com");

        assertThat(details.isEnabled()).isFalse();
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void unknownEmailDoesNotAppearInTheExceptionMessage() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageNotContaining("nobody@example.com");
    }
}
