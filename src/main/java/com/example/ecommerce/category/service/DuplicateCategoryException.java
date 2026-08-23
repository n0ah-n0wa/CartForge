package com.example.ecommerce.category.service;

public class DuplicateCategoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

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
        super(field == Field.NAME
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
