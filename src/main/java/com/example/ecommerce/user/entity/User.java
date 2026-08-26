package com.example.ecommerce.user.entity;

import com.example.ecommerce.common.persistence.VersionedEntity;
import com.example.ecommerce.user.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.Objects;

@Entity
@Table(name = "users")
@EntityListeners(UserSecurityCacheListener.class)
public class User extends VersionedEntity {

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled;

    protected User() {
    }

    public static User create(
            String email,
            String passwordHash,
            String firstName,
            String lastName,
            UserRole role) {
        User user = new User();
        user.email = normalizeEmail(email);
        user.passwordHash = requireText(passwordHash, "passwordHash");
        user.firstName = requireText(firstName, "firstName");
        user.lastName = requireText(lastName, "lastName");
        user.role = Objects.requireNonNullElse(role, UserRole.CUSTOMER);
        user.enabled = true;
        return user;
    }

    public static User registerCustomer(
            String email,
            String passwordHash,
            String firstName,
            String lastName) {
        return create(email, passwordHash, firstName, lastName, UserRole.CUSTOMER);
    }

    public void rename(String firstName, String lastName) {
        this.firstName = requireText(firstName, "firstName");
        this.lastName = requireText(lastName, "lastName");
    }

    public void replacePasswordHash(String passwordHash) {
        this.passwordHash = requireText(passwordHash, "passwordHash");
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    /**
     * Changes the persisted role. Authorization reads this value on every Bearer
     * request, so demotion takes effect before the current JWT expires.
     */
    public void assignRole(UserRole role) {
        this.role = Objects.requireNonNull(role, "role");
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "User[id=" + getId() + ", email=" + email + ", role=" + role + "]";
    }

    private static String normalizeEmail(String email) {
        return requireText(email, "email").toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
