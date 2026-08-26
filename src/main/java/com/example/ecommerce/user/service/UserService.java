package com.example.ecommerce.user.service;

import com.example.ecommerce.common.security.CurrentUserProvider;
import com.example.ecommerce.user.dto.UserResponse;
import com.example.ecommerce.user.mapper.UserMapper;
import com.example.ecommerce.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final CurrentUserProvider currentUserProvider;

    public UserService(
            UserRepository userRepository, UserMapper userMapper, CurrentUserProvider currentUserProvider) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.currentUserProvider = currentUserProvider;
    }

    public UserResponse currentProfile() {
        long userId = currentUserProvider.requireUserId();
        return userRepository
                .findById(userId)
                .map(userMapper::toResponse)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}
