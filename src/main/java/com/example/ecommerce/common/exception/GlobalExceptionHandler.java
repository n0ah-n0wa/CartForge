package com.example.ecommerce.common.exception;

import com.example.ecommerce.cart.service.CartItemNotFoundException;
import com.example.ecommerce.cart.service.CartOwnerNotFoundException;
import com.example.ecommerce.cart.service.InactiveProductForCartException;
import com.example.ecommerce.cart.service.InvalidCartQuantityException;
import com.example.ecommerce.inventory.service.InsufficientStockException;
import com.example.ecommerce.inventory.service.InvalidInventoryQuantityException;
import com.example.ecommerce.inventory.service.InventoryConflictException;
import com.example.ecommerce.auth.service.DuplicateEmailException;
import com.example.ecommerce.auth.service.InvalidCredentialsException;
import com.example.ecommerce.order.service.EmptyCartException;
import com.example.ecommerce.order.service.IdempotencyKeyConflictException;
import com.example.ecommerce.order.service.InactiveProductForCheckoutException;
import com.example.ecommerce.order.service.InvalidIdempotencyKeyException;
import com.example.ecommerce.order.service.OrderNotFoundException;
import com.example.ecommerce.order.service.OrderOwnerNotFoundException;
import com.example.ecommerce.order.OrderStatusTransitionException;
import com.example.ecommerce.category.service.CategoryInUseException;
import com.example.ecommerce.category.service.CategoryNotFoundException;
import com.example.ecommerce.category.service.DuplicateCategoryException;
import com.example.ecommerce.common.pagination.InvalidSortException;
import com.example.ecommerce.product.service.DuplicateProductException;
import com.example.ecommerce.product.service.InvalidProductCategoryException;
import com.example.ecommerce.product.service.InvalidProductQueryException;
import com.example.ecommerce.product.service.ProductNotFoundException;
import com.example.ecommerce.product.service.ProductVersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String PRODUCT_VERSION_CONFLICT_MESSAGE = "The product was modified concurrently";

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiErrorResponse> invalidCredentials(
            InvalidCredentialsException exception, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateEmailException.class)
    ResponseEntity<ApiErrorResponse> duplicateEmail(
            DuplicateEmailException exception, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "DUPLICATE_EMAIL", exception.getMessage(), request);
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    ResponseEntity<ApiErrorResponse> categoryNotFound(CategoryNotFoundException exception, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ApiErrorResponse> productNotFound(ProductNotFoundException exception, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(CartItemNotFoundException.class)
    ResponseEntity<ApiErrorResponse> cartItemNotFound(CartItemNotFoundException exception, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(CartOwnerNotFoundException.class)
    ResponseEntity<ApiErrorResponse> cartOwnerNotFound(CartOwnerNotFoundException exception, HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "CART_OWNER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(InactiveProductForCartException.class)
    ResponseEntity<ApiErrorResponse> inactiveProductForCart(
            InactiveProductForCartException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INACTIVE_PRODUCT", exception.getMessage(), request);
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<ApiErrorResponse> insufficientStock(
            InsufficientStockException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidInventoryQuantityException.class)
    ResponseEntity<ApiErrorResponse> invalidInventoryQuantity(
            InvalidInventoryQuantityException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_INVENTORY_QUANTITY", exception.getMessage(), request);
    }

    @ExceptionHandler(InventoryConflictException.class)
    ResponseEntity<ApiErrorResponse> inventoryConflict(
            InventoryConflictException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "INVENTORY_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(EmptyCartException.class)
    ResponseEntity<ApiErrorResponse> emptyCart(EmptyCartException exception, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "EMPTY_CART", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ResponseEntity<ApiErrorResponse> invalidIdempotencyKey(
            InvalidIdempotencyKeyException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ResponseEntity<ApiErrorResponse> idempotencyKeyConflict(
            IdempotencyKeyConflictException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), request);
    }

    @ExceptionHandler(InactiveProductForCheckoutException.class)
    ResponseEntity<ApiErrorResponse> inactiveProductForCheckout(
            InactiveProductForCheckoutException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INACTIVE_PRODUCT", exception.getMessage(), request);
    }

    @ExceptionHandler(OrderOwnerNotFoundException.class)
    ResponseEntity<ApiErrorResponse> orderOwnerNotFound(
            OrderOwnerNotFoundException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.UNAUTHORIZED, "ORDER_OWNER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ApiErrorResponse> orderNotFound(OrderNotFoundException exception, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(OrderStatusTransitionException.class)
    ResponseEntity<ApiErrorResponse> orderStatusTransition(
            OrderStatusTransitionException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ORDER_STATUS_TRANSITION", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidCartQuantityException.class)
    ResponseEntity<ApiErrorResponse> invalidCartQuantity(
            InvalidCartQuantityException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_CART_QUANTITY", exception.getMessage(), request);
    }

    @ExceptionHandler(CategoryInUseException.class)
    ResponseEntity<ApiErrorResponse> categoryInUse(CategoryInUseException exception, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "CATEGORY_IN_USE", exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateCategoryException.class)
    ResponseEntity<ApiErrorResponse> duplicateCategory(
            DuplicateCategoryException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(DuplicateProductException.class)
    ResponseEntity<ApiErrorResponse> duplicateProduct(
            DuplicateProductException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler(ProductVersionConflictException.class)
    ResponseEntity<ApiErrorResponse> productVersionConflict(
            ProductVersionConflictException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "PRODUCT_VERSION_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> optimisticLockingFailure(
            OptimisticLockingFailureException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "PRODUCT_VERSION_CONFLICT", PRODUCT_VERSION_CONFLICT_MESSAGE, request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> pessimisticLockingFailure(
            PessimisticLockingFailureException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.CONFLICT,
                "INVENTORY_CONFLICT",
                "The resource was modified concurrently",
                request);
    }

    @ExceptionHandler(InvalidProductCategoryException.class)
    ResponseEntity<ApiErrorResponse> invalidProductCategory(
            InvalidProductCategoryException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_CATEGORY", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validationFailure(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .or(() -> exception.getBindingResult().getGlobalErrors().stream()
                        .findFirst()
                        .map(error -> error.getDefaultMessage()))
                .orElse("Validation failed");
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> missingRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                exception.getParameterName() + " is required",
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> argumentTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getName() + " is invalid", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return respond(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request body is missing or malformed",
                request);
    }

    @ExceptionHandler(InvalidProductQueryException.class)
    ResponseEntity<ApiErrorResponse> invalidProductQuery(
            InvalidProductQueryException exception,
            HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidSortException.class)
    ResponseEntity<ApiErrorResponse> invalidSort(InvalidSortException exception, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_SORT", exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> illegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> dataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        String detail = exception.getMessage();
        if (detail != null && detail.contains("uq_categories_name")) {
            return respond(HttpStatus.CONFLICT, "DUPLICATE_CATEGORY_NAME", "Category name already exists", request);
        }
        if (detail != null && detail.contains("uq_categories_slug")) {
            return respond(HttpStatus.CONFLICT, "DUPLICATE_CATEGORY_SLUG", "Category slug already exists", request);
        }
        if (detail != null && detail.contains("uq_products_sku")) {
            return respond(HttpStatus.CONFLICT, "DUPLICATE_PRODUCT_SKU", "Product SKU already exists", request);
        }
        if (detail != null && detail.contains("uq_products_slug")) {
            return respond(HttpStatus.CONFLICT, "DUPLICATE_PRODUCT_SLUG", "Product slug already exists", request);
        }
        return respond(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION", "A database constraint was violated", request);
    }

    private static ResponseEntity<ApiErrorResponse> respond(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI()));
    }
}
