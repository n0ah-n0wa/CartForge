package com.example.ecommerce.common.exception;

import com.example.ecommerce.auth.service.DuplicateEmailException;
import com.example.ecommerce.auth.service.InvalidCredentialsException;
import com.example.ecommerce.cart.service.CartItemNotFoundException;
import com.example.ecommerce.cart.service.CartOwnerNotFoundException;
import com.example.ecommerce.cart.service.InactiveProductForCartException;
import com.example.ecommerce.cart.service.InvalidCartQuantityException;
import com.example.ecommerce.category.service.CategoryInUseException;
import com.example.ecommerce.category.service.CategoryNotFoundException;
import com.example.ecommerce.category.service.DuplicateCategoryException;
import com.example.ecommerce.common.logging.CorrelationIds;
import com.example.ecommerce.common.pagination.InvalidSortException;
import com.example.ecommerce.inventory.service.InsufficientStockException;
import com.example.ecommerce.inventory.service.InvalidInventoryQuantityException;
import com.example.ecommerce.inventory.service.InventoryConflictException;
import com.example.ecommerce.order.OrderStatusTransitionException;
import com.example.ecommerce.order.service.EmptyCartException;
import com.example.ecommerce.order.service.IdempotencyKeyConflictException;
import com.example.ecommerce.order.service.InactiveProductForCheckoutException;
import com.example.ecommerce.order.service.InvalidIdempotencyKeyException;
import com.example.ecommerce.order.service.OrderNotFoundException;
import com.example.ecommerce.order.service.OrderOwnerNotFoundException;
import com.example.ecommerce.product.service.DuplicateProductException;
import com.example.ecommerce.product.service.InvalidProductCategoryException;
import com.example.ecommerce.product.service.InvalidProductQueryException;
import com.example.ecommerce.product.service.ProductNotFoundException;
import com.example.ecommerce.product.service.ProductVersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central API error mapping. Every client-visible body uses {@link ApiErrorResponse};
 * stack traces, SQL, class names, and credentials stay out of the response.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String CONCURRENT_MODIFICATION_MESSAGE = "The resource was modified concurrently";

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiErrorResponse> invalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> authenticationFailure(
            AuthenticationException exception, HttpServletRequest request) {
        return ApiErrors.entity(
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> accessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is denied", request);
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<ApiErrorResponse> duplicateEmail(
            DuplicateEmailException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", exception.getMessage(), request);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    ResponseEntity<ApiErrorResponse> categoryNotFound(
            CategoryNotFoundException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ApiErrorResponse> productNotFound(
            ProductNotFoundException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(CartItemNotFoundException.class)
    ResponseEntity<ApiErrorResponse> cartItemNotFound(
            CartItemNotFoundException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ApiErrorResponse> orderNotFound(OrderNotFoundException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(CartOwnerNotFoundException.class)
    ResponseEntity<ApiErrorResponse> cartOwnerNotFound(
            CartOwnerNotFoundException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.UNAUTHORIZED, "CART_OWNER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(OrderOwnerNotFoundException.class)
    ResponseEntity<ApiErrorResponse> orderOwnerNotFound(
            OrderOwnerNotFoundException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.UNAUTHORIZED, "ORDER_OWNER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler({InactiveProductForCartException.class, InactiveProductForCheckoutException.class})
    ResponseEntity<ApiErrorResponse> inactiveProduct(RuntimeException exception, HttpServletRequest request) {
        log.warn(
                "event=checkout_or_cart_conflict code=INACTIVE_PRODUCT path={} message={}",
                request.getRequestURI(),
                exception.getMessage());
        return ApiErrors.entity(HttpStatus.BAD_REQUEST, "INACTIVE_PRODUCT", exception.getMessage(), request);
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ApiErrorResponse> insufficientStock(
            InsufficientStockException exception, HttpServletRequest request) {
        log.warn(
                "event=checkout_or_cart_conflict code=INSUFFICIENT_STOCK path={} message={}",
                request.getRequestURI(),
                exception.getMessage());
        return ApiErrors.entity(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidInventoryQuantityException.class)
    ResponseEntity<ApiErrorResponse> invalidInventoryQuantity(
            InvalidInventoryQuantityException exception, HttpServletRequest request) {
        return ApiErrors.entity(
                HttpStatus.BAD_REQUEST, "INVALID_INVENTORY_QUANTITY", exception.getMessage(), request);
    }

    @ExceptionHandler(InventoryConflictException.class)
    ResponseEntity<ApiErrorResponse> inventoryConflict(
            InventoryConflictException exception, HttpServletRequest request) {
        log.warn(
                "event=inventory_conflict code=INVENTORY_CONFLICT path={} message={}",
                request.getRequestURI(),
                exception.getMessage());
        return ApiErrors.entity(HttpStatus.CONFLICT, "INVENTORY_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(EmptyCartException.class)
    ResponseEntity<ApiErrorResponse> emptyCart(EmptyCartException exception, HttpServletRequest request) {
        log.warn("event=checkout_failed code=EMPTY_CART path={}", request.getRequestURI());
        return ApiErrors.entity(HttpStatus.CONFLICT, "EMPTY_CART", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ResponseEntity<ApiErrorResponse> invalidIdempotencyKey(
            InvalidIdempotencyKeyException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ApiErrorResponse> idempotencyKeyConflict(
            IdempotencyKeyConflictException exception, HttpServletRequest request) {
        log.warn("event=checkout_failed code=IDEMPOTENCY_KEY_REUSED path={}", request.getRequestURI());
        return ApiErrors.entity(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), request);
    }

    @ExceptionHandler(OrderStatusTransitionException.class)
    ResponseEntity<ApiErrorResponse> orderStatusTransition(
            OrderStatusTransitionException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.CONFLICT, "ORDER_STATUS_TRANSITION", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidCartQuantityException.class)
    ResponseEntity<ApiErrorResponse> invalidCartQuantity(
            InvalidCartQuantityException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.BAD_REQUEST, "INVALID_CART_QUANTITY", exception.getMessage(), request);
    }

    @ExceptionHandler(CategoryInUseException.class)
    ResponseEntity<ApiErrorResponse> categoryInUse(CategoryInUseException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.CONFLICT, "CATEGORY_IN_USE", exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateCategoryException.class)
    ResponseEntity<ApiErrorResponse> duplicateCategory(
            DuplicateCategoryException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.CONFLICT, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateProductException.class)
    ResponseEntity<ApiErrorResponse> duplicateProduct(
            DuplicateProductException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.CONFLICT, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(ProductVersionConflictException.class)
    ResponseEntity<ApiErrorResponse> productVersionConflict(
            ProductVersionConflictException exception, HttpServletRequest request) {
        return ApiErrors.entity(
                HttpStatus.CONFLICT, "PRODUCT_VERSION_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> optimisticLockingFailure(
            OptimisticLockingFailureException exception, HttpServletRequest request) {
        return ApiErrors.entity(
                HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", CONCURRENT_MODIFICATION_MESSAGE, request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> pessimisticLockingFailure(
            PessimisticLockingFailureException exception, HttpServletRequest request) {
        return ApiErrors.entity(
                HttpStatus.CONFLICT, "INVENTORY_CONFLICT", CONCURRENT_MODIFICATION_MESSAGE, request);
    }

    @ExceptionHandler(InvalidProductCategoryException.class)
    ResponseEntity<ApiErrorResponse> invalidProductCategory(
            InvalidProductCategoryException exception, HttpServletRequest request) {
        return ApiErrors.entity(
                HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_CATEGORY", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidProductQueryException.class)
    ResponseEntity<ApiErrorResponse> invalidProductQuery(
            InvalidProductQueryException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidSortException.class)
    ResponseEntity<ApiErrorResponse> invalidSort(InvalidSortException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.BAD_REQUEST, "INVALID_SORT", exception.getMessage(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> constraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .orElse("Validation failed");
        return ApiErrors.entity(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> argumentTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return ApiErrors.entity(
                HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getName() + " is invalid", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> illegalArgument(
            IllegalArgumentException exception, HttpServletRequest request) {
        return ApiErrors.entity(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> dataIntegrityViolation(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        String detail = exception.getMostSpecificCause().getMessage();
        if (detail == null) {
            detail = exception.getMessage();
        }
        if (detail != null) {
            if (detail.contains("uq_categories_name")) {
                return ApiErrors.entity(
                        HttpStatus.CONFLICT, "DUPLICATE_CATEGORY_NAME", "Category name already exists", request);
            }
            if (detail.contains("uq_categories_slug")) {
                return ApiErrors.entity(
                        HttpStatus.CONFLICT, "DUPLICATE_CATEGORY_SLUG", "Category slug already exists", request);
            }
            if (detail.contains("uq_products_sku")) {
                return ApiErrors.entity(
                        HttpStatus.CONFLICT, "DUPLICATE_PRODUCT_SKU", "Product SKU already exists", request);
            }
            if (detail.contains("uq_products_slug")) {
                return ApiErrors.entity(
                        HttpStatus.CONFLICT, "DUPLICATE_PRODUCT_SLUG", "Product slug already exists", request);
            }
            if (detail.contains("uq_users_email")) {
                return ApiErrors.entity(
                        HttpStatus.CONFLICT, "DUPLICATE_EMAIL", "Email already registered", request);
            }
            if (detail.contains("uq_orders_order_number")) {
                return ApiErrors.entity(
                        HttpStatus.CONFLICT, "DUPLICATE_ORDER_NUMBER", "Order number already exists", request);
            }
            if (detail.contains("uq_checkout_idempotency")) {
                return ApiErrors.entity(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request",
                        request);
            }
        }
        return ApiErrors.entity(
                HttpStatus.CONFLICT,
                "DATA_INTEGRITY_VIOLATION",
                "A database constraint was violated",
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        log.error(
                "event=unexpected_error path={} correlationId={}",
                request.getRequestURI(),
                CorrelationIds.currentOrEmpty(),
                exception);
        return ApiErrors.entity(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .or(() -> exception.getBindingResult().getGlobalErrors().stream()
                        .findFirst()
                        .map(error -> error.getDefaultMessage()))
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrors.of(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrors.of(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        "Validation failed",
                        path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrors.of(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        exception.getParameterName() + " is required",
                        path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrors.of(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        "Request body is missing or malformed",
                        path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiErrors.of(
                        HttpStatus.METHOD_NOT_ALLOWED,
                        "METHOD_NOT_ALLOWED",
                        "HTTP method is not supported for this path",
                        path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiErrors.of(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", path(request)));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        if (body instanceof ApiErrorResponse) {
            return ResponseEntity.status(statusCode).headers(headers).body(body);
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (status.is5xxServerError()) {
            log.error(
                    "event=spring_mvc_error path={} correlationId={}",
                    path(request),
                    CorrelationIds.currentOrEmpty(),
                    exception);
        }
        String code = status.is4xxClientError() ? "REQUEST_ERROR" : "INTERNAL_ERROR";
        String message = status.is4xxClientError()
                ? "The request could not be processed"
                : "An unexpected error occurred";
        return ResponseEntity.status(status)
                .headers(headers)
                .body(ApiErrors.of(status, code, message, path(request)));
    }

    private static String path(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return "";
    }
}
