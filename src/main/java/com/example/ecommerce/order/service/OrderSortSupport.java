package com.example.ecommerce.order.service;

import com.example.ecommerce.common.pagination.AllowedSort;
import org.springframework.data.domain.Sort;

/**
 * Explicit order-listing sort allowlist. Only these API field names may appear
 * in {@code sort} query parameters; they map to fixed entity properties.
 */
final class OrderSortSupport {

    static final AllowedSort ALLOWED = AllowedSort.builder()
            .allow("createdAt", "createdAt")
            .allow("orderNumber", "orderNumber")
            .allow("status", "status")
            .allow("totalAmount", "totalAmount")
            .defaultSort(Sort.by(Sort.Direction.DESC, "createdAt"))
            .tieBreaker("id")
            .build();

    private OrderSortSupport() {
    }
}
