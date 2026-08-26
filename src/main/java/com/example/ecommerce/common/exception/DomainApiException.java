package com.example.ecommerce.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for domain failures mapped to stable HTTP status and error codes.
 * Keeps {@link GlobalExceptionHandler} free of imports from every feature module.
 */
public class DomainApiException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    protected DomainApiException(String errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
