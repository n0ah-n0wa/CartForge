package com.example.ecommerce.category.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class CategoryInUseException extends DomainApiException {

    public CategoryInUseException(Long categoryId) {
        super("CATEGORY_IN_USE", HttpStatus.CONFLICT, "Category " + categoryId + " has products; deactivate or reassign before delete");
    }
}
