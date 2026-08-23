package com.example.ecommerce.product.service;

/**
 * Raised when a product references a missing or inactive category.
 */
public class InvalidProductCategoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long categoryId;

    public InvalidProductCategoryException(Long categoryId) {
        super("Category is missing or inactive: " + categoryId);
        this.categoryId = categoryId;
    }

    public Long getCategoryId() {
        return categoryId;
    }
}
