package com.example.ecommerce.order.entity;

import com.example.ecommerce.common.persistence.BaseEntity;
import com.example.ecommerce.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;

/**
 * Successful checkout keyed by authenticated user and client {@code Idempotency-Key}.
 * Rows are written only when the order transaction commits.
 */
@Entity
@Table(
        name = "checkout_idempotency_keys",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_checkout_idempotency_user_key",
                    columnNames = {"user_id", "idempotency_key"}),
            @UniqueConstraint(name = "uq_checkout_idempotency_order", columnNames = "order_id")
        })
public class CheckoutIdempotencyKey extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_checkout_idempotency_users"))
    private User user;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "order_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_checkout_idempotency_orders"))
    private Order order;

    protected CheckoutIdempotencyKey() {
    }

    public static CheckoutIdempotencyKey completed(
            User user, String idempotencyKey, String requestFingerprint, Order order) {
        CheckoutIdempotencyKey record = new CheckoutIdempotencyKey();
        record.user = Objects.requireNonNull(user, "user is required");
        record.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        record.requestFingerprint =
                Objects.requireNonNull(requestFingerprint, "requestFingerprint is required");
        record.order = Objects.requireNonNull(order, "order is required");
        return record;
    }

    public User getUser() {
        return user;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Order getOrder() {
        return order;
    }
}
