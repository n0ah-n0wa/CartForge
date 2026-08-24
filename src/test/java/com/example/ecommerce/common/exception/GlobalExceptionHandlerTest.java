package com.example.ecommerce.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ecommerce.inventory.service.InsufficientStockException;
import com.example.ecommerce.order.OrderStatus;
import com.example.ecommerce.order.OrderStatusTransitionException;
import com.example.ecommerce.product.service.ProductNotFoundException;
import com.example.ecommerce.product.service.ProductVersionConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Exercises every major error category through {@link GlobalExceptionHandler}
 * without Spring Security filters or a database.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validationErrorsUseTheStandardEnvelope() throws Exception {
        mockMvc.perform(post("/probe/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/probe/validation"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void authenticationErrorsAreUnauthorized() throws Exception {
        mockMvc.perform(get("/probe/authentication"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"));
    }

    @Test
    void authorizationErrorsAreForbidden() throws Exception {
        mockMvc.perform(get("/probe/authorization"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access is denied"));
    }

    @Test
    void notFoundErrorsAreNotFound() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Product not found: 42"));
    }

    @Test
    void businessConflictsAreConflict() throws Exception {
        mockMvc.perform(get("/probe/business-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Insufficient stock")));
    }

    @Test
    void optimisticLockingConflictsAreConflictWithoutInternals() throws Exception {
        mockMvc.perform(get("/probe/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"))
                .andExpect(jsonPath("$.message").value("The resource was modified concurrently"));

        mockMvc.perform(get("/probe/product-version"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_VERSION_CONFLICT"));
    }

    @Test
    void databaseConstraintConflictsAreConflictWithoutSql() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PRODUCT_SKU"))
                .andExpect(jsonPath("$.message").value("Product SKU already exists"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("insert into");
        assertThat(body).doesNotContain("Detail:");
        assertThat(body).doesNotContain("PSQLException");

        MvcResult unknown = mockMvc.perform(get("/probe/data-integrity-unknown"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.message").value("A database constraint was violated"))
                .andReturn();
        assertThat(unknown.getResponse().getContentAsString())
                .doesNotContain("fk_unknown")
                .doesNotContain("password=");
    }

    @Test
    void statusTransitionConflictsAreConflict() throws Exception {
        mockMvc.perform(get("/probe/status-transition"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_TRANSITION"));
    }

    @Test
    void unexpectedErrorsAreInternalWithoutLeakingSecrets() throws Exception {
        MvcResult result = mockMvc.perform(get("/probe/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/probe/unexpected"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("password");
        assertThat(body).doesNotContain("eyJ");
        assertThat(body).doesNotContain("PSQLException");
        assertThat(body).doesNotContain("SELECT");
        assertThat(body).doesNotContain("stackTrace");
        assertThat(body).doesNotContain("IllegalStateException");
    }

    @Test
    void malformedBodiesAreValidationErrors() throws Exception {
        mockMvc.perform(post("/probe/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"));
    }

    @RestController
    static class ErrorProbeController {

        @PostMapping("/probe/validation")
        void validation(@Valid @RequestBody ProbeRequest request) {
            // never reached for invalid payloads
        }

        @GetMapping("/probe/authentication")
        void authentication() {
            throw new BadCredentialsException("bad credentials for user secret-user");
        }

        @GetMapping("/probe/authorization")
        void authorization() {
            throw new AccessDeniedException("Denied for ROLE_CUSTOMER on /admin");
        }

        @GetMapping("/probe/not-found")
        void notFound() {
            throw new ProductNotFoundException(42L);
        }

        @GetMapping("/probe/business-conflict")
        void businessConflict() {
            throw new InsufficientStockException(7L, 1, 5);
        }

        @GetMapping("/probe/optimistic-lock")
        void optimisticLock() {
            throw new OptimisticLockingFailureException(
                    "Row was updated or deleted by another transaction for entity [com.example.ecommerce.product.entity.Product]");
        }

        @GetMapping("/probe/product-version")
        void productVersion() {
            throw new ProductVersionConflictException(9L, 0L, 2L);
        }

        @GetMapping("/probe/data-integrity")
        void dataIntegrity() {
            throw new DataIntegrityViolationException(
                    "ERROR: duplicate key value violates unique constraint \"uq_products_sku\"\n"
                            + "Detail: Key (sku)=(KB-001) already exists.\n"
                            + "insert into products ... password=not-a-secret");
        }

        @GetMapping("/probe/data-integrity-unknown")
        void dataIntegrityUnknown() {
            throw new DataIntegrityViolationException(
                    "ERROR: insert into secret_table violates fk_unknown password=db-secret");
        }

        @GetMapping("/probe/status-transition")
        void statusTransition() {
            throw new OrderStatusTransitionException(OrderStatus.DELIVERED, OrderStatus.CANCELLED);
        }

        @GetMapping("/probe/unexpected")
        void unexpected() {
            throw new IllegalStateException(
                    "org.postgresql.util.PSQLException: SELECT password FROM users WHERE token='eyJhbGciOiJIUzI1NiJ9'");
        }

        record ProbeRequest(@NotBlank String name) {
        }
    }
}
