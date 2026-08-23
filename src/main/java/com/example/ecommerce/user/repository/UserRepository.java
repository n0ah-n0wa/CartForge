package com.example.ecommerce.user.repository;

import com.example.ecommerce.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Email lookups are spelled with {@code lower(...)} rather than Spring Data's
 * {@code IgnoreCase} keyword, which would generate {@code upper(...)} and could
 * not use the {@code uq_users_email_lower} functional index that the login path
 * depends on.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    /**
     * Serializes first-time cart creation per user so concurrent inserts cannot
     * both miss {@code uq_carts_user_id} and abort the transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
