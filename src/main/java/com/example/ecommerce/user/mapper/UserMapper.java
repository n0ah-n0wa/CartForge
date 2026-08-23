package com.example.ecommerce.user.mapper;

import com.example.ecommerce.user.dto.CreateUserCommand;
import com.example.ecommerce.user.dto.UserResponse;
import com.example.ecommerce.user.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public User toEntity(CreateUserCommand command) {
        return User.create(
                command.email(),
                command.passwordHash(),
                command.firstName(),
                command.lastName(),
                command.role());
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
