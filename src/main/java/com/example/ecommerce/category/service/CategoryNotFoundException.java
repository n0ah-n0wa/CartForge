package com.example.ecommerce.category.service;

public class CategoryNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long categoryId;

    public CategoryNotFoundException(Long categoryId) {
        super("Category not found: " + categoryId);
        this.categoryId = categoryId;
    }

    public Long getCategoryId() {
        return categoryId;
    }
}
