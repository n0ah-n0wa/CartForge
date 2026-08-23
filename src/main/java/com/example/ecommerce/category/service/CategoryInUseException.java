package com.example.ecommerce.category.service;

public class CategoryInUseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Long categoryId;

    public CategoryInUseException(Long categoryId) {
        super("Category " + categoryId + " has products; deactivate or reassign before delete");
        this.categoryId = categoryId;
    }

    public Long getCategoryId() {
        return categoryId;
    }
}
