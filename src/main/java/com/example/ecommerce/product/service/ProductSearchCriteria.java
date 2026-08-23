package com.example.ecommerce.product.service;

import com.example.ecommerce.common.pagination.PageRequests;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.util.DigestUtils;

/**
 * Validated catalog query inputs. Constructed only through
 * {@link ProductSearchCriteria#of} so invalid ranges never reach the repository
 * and cache keys match the pagination/search rules applied at query time.
 */
public record ProductSearchCriteria(
        String categorySlug,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        String searchTerm,
        int page,
        int size,
        List<String> sort
) {

    public static final int MAX_SEARCH_LENGTH = 100;

    public ProductSearchCriteria {
        sort = sort == null ? List.of() : List.copyOf(sort);
    }

    @Override
    public List<String> sort() {
        return List.copyOf(sort);
    }

    /**
     * Deterministic hash of normalized filter/sort/page inputs for
     * {@code products:{query-hash}} cache keys. Callers must build criteria via
     * {@link #of} so page/size/search/sort match the executed query.
     */
    public String cacheKey() {
        String material = String.join(
                "|",
                nullToEmpty(categorySlug),
                priceToken(minPrice),
                priceToken(maxPrice),
                nullToEmpty(searchTerm),
                String.valueOf(page),
                String.valueOf(size),
                sort.stream().map(ProductSearchCriteria::nullToEmpty).collect(Collectors.joining(",")));
        return DigestUtils.md5DigestAsHex(material.getBytes(StandardCharsets.UTF_8));
    }

    private static String priceToken(BigDecimal price) {
        return price == null ? "" : price.stripTrailingZeros().toPlainString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * @param defaultPageSize application default page size (must match
     *                        {@link PageRequests} at query time)
     * @param maxPageSize     application max page size (must match
     *                        {@link PageRequests} at query time)
     */
    public static ProductSearchCriteria of(
            String category,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String search,
            Integer page,
            Integer size,
            List<String> sort,
            int defaultPageSize,
            int maxPageSize) {
        BigDecimal normalizedMin = normalizePrice(minPrice, "minPrice");
        BigDecimal normalizedMax = normalizePrice(maxPrice, "maxPrice");
        if (normalizedMin != null && normalizedMax != null && normalizedMin.compareTo(normalizedMax) > 0) {
            throw new InvalidProductQueryException("minPrice must not be greater than maxPrice");
        }

        return new ProductSearchCriteria(
                normalizeCategory(category),
                normalizedMin,
                normalizedMax,
                normalizeSearch(search),
                resolvePage(page),
                PageRequests.resolvePageSize(size, defaultPageSize, maxPageSize),
                normalizeSort(sort));
    }

    /**
     * Convenience overload using the documented defaults (page size 20, max 100).
     * Production request handling must call the overload that receives the live
     * {@code app.pagination} settings so cache keys stay aligned with queries.
     */
    public static ProductSearchCriteria of(
            String category,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            String search,
            Integer page,
            Integer size,
            List<String> sort) {
        return of(category, minPrice, maxPrice, search, page, size, sort, 20, 100);
    }

    private static int resolvePage(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    private static BigDecimal normalizePrice(BigDecimal price, String field) {
        if (price == null) {
            return null;
        }
        if (price.signum() < 0) {
            throw new InvalidProductQueryException(field + " must not be negative");
        }
        return price;
    }

    private static String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String trimmed = search.trim();
        if (trimmed.length() > MAX_SEARCH_LENGTH) {
            throw new InvalidProductQueryException(
                    "search must not exceed " + MAX_SEARCH_LENGTH + " characters");
        }
        // Lowercase so cache keys match case-insensitive LIKE on search_text.
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Lowercases field and direction so {@code Price,ASC} and {@code price,asc}
     * share one cache entry (AllowedSort already matches case-insensitively).
     */
    private static List<String> normalizeSort(List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(sort.size());
        for (String raw : sort) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String trimmed = raw.trim();
            String[] parts = trimmed.split(",", 2);
            String field = parts[0].trim().toLowerCase(Locale.ROOT);
            if (parts.length == 1 || parts[1].isBlank()) {
                normalized.add(field);
            } else {
                normalized.add(field + "," + parts[1].trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(normalized);
    }
}
