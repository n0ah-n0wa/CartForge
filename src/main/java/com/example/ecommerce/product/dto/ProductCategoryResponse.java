package com.example.ecommerce.product.dto;

/**
 * Category summary embedded in a product response. Mapping it requires the
 * category association to be fetched (see {@code findWithCategoryBySlug}).
 */
public record ProductCategoryResponse(
        Long id,
        String name,
        String slug
) {
}
