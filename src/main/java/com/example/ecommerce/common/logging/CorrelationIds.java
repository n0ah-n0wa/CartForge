package com.example.ecommerce.common.logging;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Correlation ID constants and safe resolution. Client values are accepted only
 * when they cannot inject newlines or other control characters into logs.
 */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_ATTRIBUTE = "com.example.ecommerce.correlationId";

    static final int MAX_LENGTH = 128;

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1," + MAX_LENGTH + "}$");

    private CorrelationIds() {
    }

    /**
     * Returns a sanitized client value, or a newly generated UUID when the
     * inbound header is missing or unsafe.
     */
    public static String resolve(String incoming) {
        if (incoming == null) {
            return generate();
        }
        String trimmed = incoming.trim();
        if (!SAFE.matcher(trimmed).matches()) {
            return generate();
        }
        return trimmed;
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    public static Optional<String> current() {
        String value = MDC.get(MDC_KEY);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public static String currentOrEmpty() {
        return current().orElse("");
    }
}
