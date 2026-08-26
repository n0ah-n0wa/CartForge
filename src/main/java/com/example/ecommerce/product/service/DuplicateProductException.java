package com.example.ecommerce.product.service;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class DuplicateProductException extends DomainApiException {

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
        super(
                field.code(),
                HttpStatus.CONFLICT,
                field == Field.SKU
                        ? "Product SKU already exists: " + value
                        : "Product slug already exists: " + value);
        this.field = field;
    }

    public Field getField() {
        return field;
    }

    public String code() {
        return errorCode();
    }
}
