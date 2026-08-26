package com.example.ecommerce.common.pagination;

import com.example.ecommerce.common.exception.DomainApiException;
import org.springframework.http.HttpStatus;

public class InvalidSortException extends DomainApiException {

    public InvalidSortException(String message) {
        super("INVALID_SORT", HttpStatus.BAD_REQUEST, message);
    }
}
