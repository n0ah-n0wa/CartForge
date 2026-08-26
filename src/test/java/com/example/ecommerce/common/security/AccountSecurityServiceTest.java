package com.example.ecommerce.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

@ExtendWith(MockitoExtension.class)
class AccountSecurityServiceTest {

    private static final long USER_ID = 42L;

    @Mock
    private UserRepository userRepository;

    private AccountSecurityService accountSecurityService;

    @BeforeEach
    void setUp() {
        accountSecurityService = new AccountSecurityService(userRepository, 10);
    }

    @Test
    void cachesEnabledAccountSnapshotsWithinTtl() {
        User user = enabledUser(UserRole.CUSTOMER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        AccountSecurityService.AccountSecuritySnapshot first =
                accountSecurityService.requireEnabledSnapshot(USER_ID, user.getEmail());
        AccountSecurityService.AccountSecuritySnapshot second =
                accountSecurityService.requireEnabledSnapshot(USER_ID, user.getEmail());

        assertThat(first.role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(second.role()).isEqualTo(UserRole.CUSTOMER);
        verify(userRepository, times(1)).findById(USER_ID);
    }

    @Test
    void rejectsEmailMismatchBetweenTokenAndDatabase() {
        User user = enabledUser(UserRole.CUSTOMER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> accountSecurityService.requireEnabledSnapshot(USER_ID, "other@example.com"))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("email");
    }

    @Test
    void rejectsDisabledAccountsWithoutCachingThem() {
        User disabled = enabledUser(UserRole.CUSTOMER);
        disabled.disable();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> accountSecurityService.requireEnabledSnapshot(USER_ID, disabled.getEmail()))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("disabled");

        assertThatThrownBy(() -> accountSecurityService.requireEnabledSnapshot(USER_ID, disabled.getEmail()))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verify(userRepository, times(2)).findById(USER_ID);
    }

    @Test
    void evictForcesReloadOnNextRequest() {
        User user = enabledUser(UserRole.CUSTOMER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        accountSecurityService.requireEnabledSnapshot(USER_ID, user.getEmail());
        accountSecurityService.evict(USER_ID);
        accountSecurityService.requireEnabledSnapshot(USER_ID, user.getEmail());

        verify(userRepository, times(2)).findById(USER_ID);
    }

    private static User enabledUser(UserRole role) {
        return User.create("ada@example.com", "{bcrypt}x", "Ada", "Lovelace", role);
    }
}
