package com.example.ecommerce.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.auth.dto.RegistrationRequest;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.dto.UserResponse;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.mapper.UserMapper;
import com.example.ecommerce.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final String PLAINTEXT = "test-only-Password123!";

    @Mock
    private UserRepository userRepository;

    private PasswordEncoder passwordEncoder;
    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        registrationService = new RegistrationService(userRepository, new UserMapper(), passwordEncoder);
    }

    @Test
    void registersACustomerWithAHashedPassword() {
        when(userRepository.existsByEmailIgnoreCase("Ada@Example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse response = registrationService.register(request("Ada@Example.com"));

        ArgumentCaptor<User> persisted = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(persisted.capture());
        User user = persisted.getValue();

        assertThat(user.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(user.getPasswordHash()).isNotEqualTo(PLAINTEXT);
        assertThat(user.getPasswordHash()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(PLAINTEXT, user.getPasswordHash())).isTrue();
        assertThat(response.role()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void neverExposesThePasswordOrItsHash() {
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse response = registrationService.register(request("ada@example.com"));

        assertThat(response.toString()).doesNotContain(PLAINTEXT).doesNotContain("bcrypt");
    }

    @Test
    void hashesAreSaltedSoIdenticalPasswordsDiffer() {
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

        registrationService.register(request("ada@example.com"));
        registrationService.register(request("grace@example.com"));

        ArgumentCaptor<User> persisted = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).saveAndFlush(persisted.capture());

        assertThat(persisted.getAllValues().get(0).getPasswordHash())
                .isNotEqualTo(persisted.getAllValues().get(1).getPasswordHash());
    }

    @Test
    void rejectsAnAlreadyRegisteredEmail() {
        when(userRepository.existsByEmailIgnoreCase("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> registrationService.register(request("ada@example.com")))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessageContaining("ada@example.com");
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void translatesAConcurrentDuplicateIntoTheSameFailure() {
        when(userRepository.existsByEmailIgnoreCase(any())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("uq_users_email_lower"));

        assertThatThrownBy(() -> registrationService.register(request("ada@example.com")))
                .isInstanceOf(DuplicateEmailException.class);
    }

    private static RegistrationRequest request(String email) {
        return new RegistrationRequest(email, PLAINTEXT, "Ada", "Lovelace");
    }
}
