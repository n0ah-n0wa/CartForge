package com.example.ecommerce.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductSearchCriteriaCacheKeyTest {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @Test
    void cacheKeyIsDeterministicForEquivalentCriteria() {
        ProductSearchCriteria left = ProductSearchCriteria.of(
                "Books",
                new BigDecimal("10.50"),
                new BigDecimal("20.00"),
                "  Laptop ",
                1,
                20,
                List.of("price,asc", "name,desc"),
                DEFAULT_PAGE_SIZE,
                MAX_PAGE_SIZE);
        ProductSearchCriteria right = ProductSearchCriteria.of(
                "books",
                new BigDecimal("10.500"),
                new BigDecimal("20.0"),
                "Laptop",
                1,
                20,
                List.of("Price,ASC", "Name,DESC"),
                DEFAULT_PAGE_SIZE,
                MAX_PAGE_SIZE);

        assertThat(left.cacheKey()).isEqualTo(right.cacheKey());
        assertThat(left.cacheKey()).hasSize(32);
        assertThat(left.searchTerm()).isEqualTo("laptop");
    }

    @Test
    void cacheKeyAlignsNullPageAndSizeWithResolvedDefaults() {
        ProductSearchCriteria omitted = ProductSearchCriteria.of(
                null, null, null, null, null, null, null, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        ProductSearchCriteria explicit = ProductSearchCriteria.of(
                null, null, null, null, 0, DEFAULT_PAGE_SIZE, null, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        ProductSearchCriteria oversized = ProductSearchCriteria.of(
                null, null, null, null, -1, 500, List.of(), DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

        assertThat(omitted.page()).isZero();
        assertThat(omitted.size()).isEqualTo(DEFAULT_PAGE_SIZE);
        assertThat(omitted.cacheKey()).isEqualTo(explicit.cacheKey());
        assertThat(oversized.page()).isZero();
        assertThat(oversized.size()).isEqualTo(MAX_PAGE_SIZE);
    }

    @Test
    void cacheKeyChangesWhenFiltersDiffer() {
        ProductSearchCriteria base = ProductSearchCriteria.of(
                "books", null, null, "laptop", 0, 10, null, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        ProductSearchCriteria differentPage = ProductSearchCriteria.of(
                "books", null, null, "laptop", 1, 10, null, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        ProductSearchCriteria differentSearch = ProductSearchCriteria.of(
                "books", null, null, "mouse", 0, 10, null, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

        assertThat(base.cacheKey())
                .isNotEqualTo(differentPage.cacheKey())
                .isNotEqualTo(differentSearch.cacheKey());
    }

    @Test
    void cacheKeyChangesWhenActiveFilterDiffers() {
        ProductSearchCriteria activeOnly = ProductSearchCriteria.of(
                null, null, null, null, 0, 10, null, Boolean.TRUE, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        ProductSearchCriteria inactiveOnly = ProductSearchCriteria.of(
                null, null, null, null, 0, 10, null, Boolean.FALSE, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        ProductSearchCriteria all = ProductSearchCriteria.of(
                null, null, null, null, 0, 10, null, null, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);

        assertThat(activeOnly.cacheKey())
                .isNotEqualTo(inactiveOnly.cacheKey())
                .isNotEqualTo(all.cacheKey());
    }
}
