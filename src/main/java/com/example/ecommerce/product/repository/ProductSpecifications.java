package com.example.ecommerce.product.repository;

import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.service.ProductSearchCriteria;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds catalog filters as JPA predicates. Optional criteria become no-ops so
 * PostgreSQL can still use indexes on {@code active}, {@code category_id},
 * {@code price}, and {@code search_text}.
 */
public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> from(ProductSearchCriteria criteria) {
        return activeStatus(criteria.active())
                .and(categorySlug(criteria.categorySlug()))
                .and(minPrice(criteria.minPrice()))
                .and(maxPrice(criteria.maxPrice()))
                .and(textSearch(criteria.searchTerm()));
    }

    /**
     * {@code true}/{@code false} filter by status; {@code null} includes both
     * (administrator catalog listing).
     */
    static Specification<Product> activeStatus(Boolean active) {
        return (root, query, builder) -> {
            if (active == null) {
                return builder.conjunction();
            }
            return active ? builder.isTrue(root.get("active")) : builder.isFalse(root.get("active"));
        };
    }

    static Specification<Product> categorySlug(String slug) {
        return (root, query, builder) -> {
            if (slug == null) {
                return builder.conjunction();
            }
            // Path navigation (not an explicit join) lets Hibernate reuse the
            // EntityGraph fetch join for category instead of joining twice.
            return builder.equal(root.get("category").get("slug"), slug);
        };
    }

    static Specification<Product> minPrice(java.math.BigDecimal minPrice) {
        return (root, query, builder) -> {
            if (minPrice == null) {
                return builder.conjunction();
            }
            return builder.greaterThanOrEqualTo(root.get("price"), minPrice);
        };
    }

    static Specification<Product> maxPrice(java.math.BigDecimal maxPrice) {
        return (root, query, builder) -> {
            if (maxPrice == null) {
                return builder.conjunction();
            }
            return builder.lessThanOrEqualTo(root.get("price"), maxPrice);
        };
    }

    /**
     * Matches against the generated lowercased {@code search_text} column using
     * a LIKE pattern. User wildcards are escaped so they cannot broaden the scan.
     */
    static Specification<Product> textSearch(String searchTerm) {
        return (root, query, builder) -> {
            if (searchTerm == null) {
                return builder.conjunction();
            }
            String pattern = "%" + escapeLike(searchTerm.toLowerCase(java.util.Locale.ROOT)) + "%";
            return builder.like(root.get("searchText"), pattern, '\\');
        };
    }

    static String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
