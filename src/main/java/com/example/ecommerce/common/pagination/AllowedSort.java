package com.example.ecommerce.common.pagination;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Sort;

/**
 * Resolves client {@code sort} query values against an explicit allowlist.
 * Entity property names never come from raw user input.
 */
public final class AllowedSort {

    private final Map<String, String> apiFieldToEntityProperty;
    private final Sort defaultSort;
    private final String tieBreakerProperty;

    private AllowedSort(Map<String, String> apiFieldToEntityProperty, Sort defaultSort, String tieBreakerProperty) {
        this.apiFieldToEntityProperty = Map.copyOf(apiFieldToEntityProperty);
        this.defaultSort = Objects.requireNonNull(defaultSort, "defaultSort");
        this.tieBreakerProperty = Objects.requireNonNull(tieBreakerProperty, "tieBreakerProperty");
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses Spring-style {@code sort} values such as {@code price,asc} or
     * {@code name}. Unknown fields and invalid directions are rejected.
     */
    public Sort resolve(List<String> sortParams) {
        if (sortParams == null || sortParams.isEmpty()) {
            return withTieBreaker(defaultSort);
        }

        List<Sort.Order> orders = new ArrayList<>();
        for (String raw : sortParams) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            orders.add(parseOrder(raw.trim()));
        }

        if (orders.isEmpty()) {
            return withTieBreaker(defaultSort);
        }
        return withTieBreaker(Sort.by(orders));
    }

    private Sort.Order parseOrder(String raw) {
        String[] parts = raw.split(",");
        if (parts.length == 0 || parts.length > 2 || parts[0].isBlank()) {
            throw new InvalidSortException("Invalid sort parameter: '" + raw + "'");
        }

        String apiField = parts[0].trim();
        String entityProperty = apiFieldToEntityProperty.get(apiField.toLowerCase(Locale.ROOT));
        if (entityProperty == null) {
            throw new InvalidSortException("Unsupported sort field: '" + apiField + "'");
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length == 2) {
            direction = parseDirection(parts[1].trim(), raw);
        }
        return new Sort.Order(direction, entityProperty);
    }

    private static Sort.Direction parseDirection(String rawDirection, String rawParam) {
        if (rawDirection.equalsIgnoreCase("asc")) {
            return Sort.Direction.ASC;
        }
        if (rawDirection.equalsIgnoreCase("desc")) {
            return Sort.Direction.DESC;
        }
        throw new InvalidSortException("Invalid sort parameter: '" + rawParam + "'");
    }

    private Sort withTieBreaker(Sort sort) {
        boolean alreadyPresent = sort.stream().anyMatch(order -> order.getProperty().equals(tieBreakerProperty));
        if (alreadyPresent) {
            return sort;
        }
        return sort.and(Sort.by(Sort.Direction.ASC, tieBreakerProperty));
    }

    public static final class Builder {

        private final Map<String, String> fields = new LinkedHashMap<>();
        private Sort defaultSort = Sort.unsorted();
        private String tieBreakerProperty = "id";

        private Builder() {
        }

        /**
         * Registers an API field name that maps to a fixed entity property.
         * The API name is matched case-insensitively.
         */
        public Builder allow(String apiField, String entityProperty) {
            Objects.requireNonNull(apiField, "apiField");
            Objects.requireNonNull(entityProperty, "entityProperty");
            fields.put(apiField.toLowerCase(Locale.ROOT), entityProperty);
            return this;
        }

        public Builder defaultSort(Sort defaultSort) {
            this.defaultSort = Objects.requireNonNull(defaultSort, "defaultSort");
            return this;
        }

        public Builder tieBreaker(String entityProperty) {
            this.tieBreakerProperty = Objects.requireNonNull(entityProperty, "entityProperty");
            return this;
        }

        public AllowedSort build() {
            if (fields.isEmpty()) {
                throw new IllegalStateException("At least one sortable field must be allowed");
            }
            return new AllowedSort(fields, defaultSort, tieBreakerProperty);
        }
    }
}
