package com.example.ecommerce.product.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

/**
 * Raised when a product references a missing or inactive category.
 */
public class InvalidProductCategoryException extends DomainApiException {

    private static final long serialVersionUID = 1L;

    private final Long categoryId;

    public InvalidProductCategoryException(Long categoryId) {
        super(
                "INVALID_PRODUCT_CATEGORY",
                HttpStatus.BAD_REQUEST,
                "Category is missing or inactive: " + categoryId);
        this.categoryId = categoryId;
    }

    public Long getCategoryId() {
        return categoryId;
    }
}
