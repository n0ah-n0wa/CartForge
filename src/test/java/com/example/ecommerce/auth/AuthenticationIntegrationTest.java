package com.example.ecommerce.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ecommerce.auth.dto.AuthenticatedUser;
import com.example.ecommerce.auth.dto.LoginRequest;
import com.example.ecommerce.auth.dto.RegistrationRequest;
import com.example.ecommerce.auth.service.AuthenticationService;
import com.example.ecommerce.auth.service.DuplicateEmailException;
import com.example.ecommerce.auth.service.InvalidCredentialsException;
import com.example.ecommerce.auth.service.RegistrationService;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.dto.UserResponse;
import com.example.ecommerce.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.cache.type=simple",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
@ActiveProfiles("test")
@Testcontainers
@Transactional
class AuthenticationIntegrationTest {

    private static final String PLAINTEXT = "test-only-Password123!";

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
    private RegistrationService registrationService;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registersACustomerAndStoresOnlyAHash() {
        UserResponse response = registrationService.register(
                new RegistrationRequest("Ada@Example.com", PLAINTEXT, "Ada", "Lovelace"));

        assertThat(response.id()).isNotNull();
        assertThat(response.email()).isEqualTo("ada@example.com");
        assertThat(response.role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(response.enabled()).isTrue();

        String stored = jdbcTemplate.queryForObject(
                "select password_hash from users where email = 'ada@example.com'", String.class);
        assertThat(stored).isNotNull().isNotEqualTo(PLAINTEXT).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(PLAINTEXT, stored)).isTrue();
    }

    @Test
    void noColumnEverHoldsThePlaintextPassword() {
        registrationService.register(
                new RegistrationRequest("ada@example.com", PLAINTEXT, "Ada", "Lovelace"));

        Integer leaks = jdbcTemplate.queryForObject(
                """
                select count(*) from users
                where email like ? or password_hash like ? or first_name like ? or last_name like ?
                """,
                Integer.class,
                "%" + PLAINTEXT + "%",
                "%" + PLAINTEXT + "%",
                "%" + PLAINTEXT + "%",
                "%" + PLAINTEXT + "%");

        assertThat(leaks).isZero();
    }

    @Test
    void rejectsARepeatedRegistrationRegardlessOfCase() {
        registrationService.register(
                new RegistrationRequest("ada@example.com", PLAINTEXT, "Ada", "Lovelace"));

        assertThatThrownBy(() -> registrationService.register(
                        new RegistrationRequest("ADA@EXAMPLE.COM", PLAINTEXT, "Ada", "Lovelace")))
                .isInstanceOf(DuplicateEmailException.class);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void logsInWithTheRegisteredCredentialsIgnoringEmailCase() {
        UserResponse registered = registrationService.register(
                new RegistrationRequest("ada@example.com", PLAINTEXT, "Ada", "Lovelace"));

        AuthenticatedUser authenticated =
                authenticationService.authenticate(new LoginRequest("ADA@Example.com", PLAINTEXT));

        assertThat(authenticated.userId()).isEqualTo(registered.id());
        assertThat(authenticated.email()).isEqualTo("ada@example.com");
        assertThat(authenticated.role()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void rejectsInvalidCredentials() {
        registrationService.register(
                new RegistrationRequest("ada@example.com", PLAINTEXT, "Ada", "Lovelace"));

        assertThatThrownBy(() -> authenticationService.authenticate(
                        new LoginRequest("ada@example.com", "wrong-Password123!")))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> authenticationService.authenticate(
                        new LoginRequest("nobody@example.com", PLAINTEXT)))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void looksUpSecurityPrincipalsByEmailWithTheirRole() {
        registrationService.register(
                new RegistrationRequest("ada@example.com", PLAINTEXT, "Ada", "Lovelace"));

        UserDetails details = userDetailsService.loadUserByUsername("ADA@example.com");

        assertThat(details.getUsername()).isEqualTo("ada@example.com");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
        assertThat(details.getPassword()).startsWith("{bcrypt}");
    }

    @Test
    void userLookupFailsForAnUnknownEmailWithoutRevealingWhy() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageNotContaining("nobody@example.com");
    }
}
