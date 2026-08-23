package com.example.ecommerce.common.cache;

/**
 * Spring Cache names for catalog reads. With the Redis key prefix
 * {@code {cacheName}:}, keys match the specification examples:
 * {@code product:{id}}, {@code category:{id}}, {@code products:{query-hash}},
 * {@code categories:active}.
 */
public final class CatalogCaches {

    public static final String PRODUCT = "product";
    public static final String CATEGORY = "category";
    public static final String PRODUCTS = "products";
    public static final String CATEGORIES = "categories";

    public static final String ACTIVE_LIST_KEY = "active";

    private CatalogCaches() {
    }
}
