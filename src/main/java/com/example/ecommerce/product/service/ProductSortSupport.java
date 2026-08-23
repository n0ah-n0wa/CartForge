package com.example.ecommerce.product.service;

import com.example.ecommerce.common.pagination.AllowedSort;
import org.springframework.data.domain.Sort;

/**
 * Explicit product catalog sort allowlist. Only these API field names may appear
 * in {@code sort} query parameters; they map to fixed entity properties.
 */
final class ProductSortSupport {

    static final AllowedSort ALLOWED = AllowedSort.builder()
            .allow("name", "name")
            .allow("price", "price")
            .allow("sku", "sku")
            .allow("createdAt", "createdAt")
            .allow("stockQuantity", "stockQuantity")
            .defaultSort(Sort.by(Sort.Direction.ASC, "name"))
            .tieBreaker("id")
            .build();

    private ProductSortSupport() {
    }
}
