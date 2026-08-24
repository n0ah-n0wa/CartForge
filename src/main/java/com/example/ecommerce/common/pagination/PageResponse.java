package com.example.ecommerce.common.pagination;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Paginated API envelope. Matches the shape required by the specification.
 */
@Schema(description = "Paginated collection with page metadata")
public record PageResponse<T>(
        List<T> content,
        @Schema(example = "0") int page,
        @Schema(example = "20") int size,
        @Schema(example = "120") long totalElements,
        @Schema(example = "6") int totalPages
) {
    public PageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }
}
