package com.example.ecommerce.product.service;

public class DuplicateProductException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Field {
        SKU("DUPLICATE_PRODUCT_SKU"),
        SLUG("DUPLICATE_PRODUCT_SLUG");

        private final String code;

        Field(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Field field;

    public DuplicateProductException(Field field, String value) {
        super(field == Field.SKU
                ? "Product SKU already exists: " + value
                : "Product slug already exists: " + value);
        this.field = field;
    }

    public String code() {
        return field.code();
    }

    public Field getField() {
        return field;
    }
}
