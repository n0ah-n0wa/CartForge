package com.example.ecommerce.auth.service;

import com.example.ecommerce.auth.dto.RegistrationRequest;
import com.example.ecommerce.user.UserRole;
import com.example.ecommerce.user.dto.UserResponse;
import com.example.ecommerce.user.entity.User;
import com.example.ecommerce.user.mapper.UserMapper;
import com.example.ecommerce.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(
            UserRepository userRepository,
            UserMapper userMapper,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Registers a customer. The plaintext password is hashed before the entity is
     * built and is never held on the entity, so it cannot reach the database.
     * The returned representation carries no hash.
     */
    public UserResponse register(RegistrationRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new DuplicateEmailException(request.email());
        }

        User user = User.create(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.firstName(),
                request.lastName(),
                UserRole.CUSTOMER);

        try {
            UserResponse response = userMapper.toResponse(userRepository.saveAndFlush(user));
            log.info("event=registration_succeeded userId={} email={}", response.id(), response.email());
            return response;
        } catch (DataIntegrityViolationException duplicate) {
            // The pre-check cannot close the window between two concurrent
            // registrations; uq_users_email_lower is the real guard.
            throw new DuplicateEmailException(request.email());
        }
    }
}
