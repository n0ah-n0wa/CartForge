package com.example.ecommerce.order.service;

import com.example.ecommerce.order.dto.CheckoutCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses {@code Idempotency-Key} and fingerprints equivalent checkout bodies.
 */
public final class IdempotencyKeys {

    public static final int MAX_LENGTH = 255;

    private static final Pattern PRINTABLE_ASCII = Pattern.compile("^[\\x21-\\x7E]+$");

    private IdempotencyKeys() {
    }

    /**
     * Missing header means checkout is not idempotent. A present but invalid
     * value is rejected rather than ignored.
     */
    public static Optional<String> parse(String headerValue) {
        if (headerValue == null) {
            return Optional.empty();
        }
        String key = headerValue.trim();
        if (key.isEmpty() || key.length() > MAX_LENGTH || !PRINTABLE_ASCII.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        return Optional.of(key);
    }

    public static String fingerprint(CheckoutCommand command) {
        return sha256Hex(command.shippingAddress());
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
