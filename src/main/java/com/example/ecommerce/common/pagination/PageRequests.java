package com.example.ecommerce.common.pagination;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds bounded {@link Pageable} instances so listing endpoints never request
 * an unbounded result set.
 */
public final class PageRequests {

    private PageRequests() {
    }

    public static Pageable of(Integer page, Integer size, int defaultPageSize, int maxPageSize, Sort sort) {
        if (defaultPageSize < 1) {
            throw new IllegalArgumentException("defaultPageSize must be at least 1");
        }
        if (maxPageSize < defaultPageSize) {
            throw new IllegalArgumentException("maxPageSize must be >= defaultPageSize");
        }
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = resolvePageSize(size, defaultPageSize, maxPageSize);
        return PageRequest.of(pageNumber, pageSize, sort == null ? Sort.unsorted() : sort);
    }

    public static int resolvePageSize(Integer size, int defaultPageSize, int maxPageSize) {
        if (size == null || size < 1) {
            return defaultPageSize;
        }
        return Math.min(size, maxPageSize);
    }
}
