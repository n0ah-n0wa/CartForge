package com.example.ecommerce.category.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class DuplicateCategoryException extends DomainApiException {

    public enum Field {
        NAME("DUPLICATE_CATEGORY_NAME"),
        SLUG("DUPLICATE_CATEGORY_SLUG");

        private final String code;

        Field(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Field field;

    public DuplicateCategoryException(Field field, String value) {
        super(
                field.code(),
                HttpStatus.CONFLICT,
                field == Field.NAME
                        ? "Category name already exists: " + value
                        : "Category slug already exists: " + value);
        this.field = field;
    }

    public String code() {
        return field.code();
    }

    public Field getField() {
        return field;
    }
}
