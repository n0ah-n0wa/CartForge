package com.example.ecommerce.common.pagination;

import java.util.List;

/**
 * Paginated API envelope. Matches the shape required by the specification.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public PageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
