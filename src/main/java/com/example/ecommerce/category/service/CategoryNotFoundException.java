package com.example.ecommerce.category.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class CategoryNotFoundException extends DomainApiException {

    public CategoryNotFoundException(Long id) {
        super("CATEGORY_NOT_FOUND", HttpStatus.NOT_FOUND, "Category not found: " + id);
    }
}
