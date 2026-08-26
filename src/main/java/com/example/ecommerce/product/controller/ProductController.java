package com.example.ecommerce.product.controller;

import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.common.security.CurrentUserProvider;
import com.example.ecommerce.common.security.RequireAdmin;
import com.example.ecommerce.product.dto.CreateProductCommand;
import com.example.ecommerce.product.dto.PatchProductCommand;
import com.example.ecommerce.product.dto.ProductResponse;
import com.example.ecommerce.product.dto.UpdateProductCommand;
import com.example.ecommerce.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Product catalog")
public class ProductController {

    private final ProductService productService;
    private final CurrentUserProvider currentUser;

    public ProductController(ProductService productService, CurrentUserProvider currentUser) {
        this.productService = productService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(
            summary = "Search products",
            description = "Supports category slug, minPrice/maxPrice, case-insensitive text search "
                    + "(name/sku/description), allowlisted sorting (name, price, sku, createdAt, "
                    + "stockQuantity), bounded pagination, and active-status filtering. "
                    + "Anonymous and customer callers always receive active products only; "
                    + "the optional active query parameter is available to administrators "
                    + "(true, false, or omit for both). Example: "
                    + "?category=electronics&minPrice=100&maxPrice=2000&search=laptop&sort=price,asc. "
                    + "Page size is capped by app.pagination.max-page-size.")
    @ApiResponse(responseCode = "200", description = "Paged products")
    @ApiResponse(responseCode = "400", description = "Invalid page, size, sort, or filter parameter")
    public PageResponse<ProductResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String search,
            @Parameter(description = "Administrator only. true=active, false=inactive, omit=both.")
            @RequestParam(required = false) Boolean active,
            HttpServletRequest request) {
        // Read raw sort values so "sort=price,asc" stays one token. Binding to
        // List/String[] would CSV-split on the comma and treat "asc" as a field name.
        // Page/size are resolved here so Redis cache keys match the query actually run.
        return productService.searchResponsesForCaller(
                category,
                minPrice,
                maxPrice,
                search,
                page,
                size,
                sortParams(request),
                active,
                currentUser.isAdmin());
    }

    private static List<String> sortParams(HttpServletRequest request) {
        String[] values = request.getParameterValues("sort");
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.asList(values);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Retrieve a product",
            description = "Public callers receive active products only. Administrators may retrieve inactive products.")
    @ApiResponse(responseCode = "200", description = "Product found")
    @ApiResponse(responseCode = "404", description = "Product not found or inactive for public callers")
    public ProductResponse get(@PathVariable Long id) {
        return productService.getResponse(id, currentUser.isAdmin());
    }

    @PostMapping
    @RequireAdmin
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a product", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Product created")
    @ApiResponse(responseCode = "400", description = "Validation failure or inactive/missing category")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "409", description = "Duplicate SKU or slug")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductCommand command) {
        ProductResponse body = productService.createResponse(command);
        return ResponseEntity.created(URI.create("/api/v1/products/" + body.id())).body(body);
    }

    @PutMapping("/{id}")
    @RequireAdmin
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Replace a product",
            description = "Requires the current optimistic-locking version.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Product updated")
    @ApiResponse(responseCode = "400", description = "Validation failure or inactive/missing category")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "Version conflict or duplicate slug")
    public ProductResponse replace(@PathVariable Long id, @Valid @RequestBody UpdateProductCommand command) {
        return productService.updateResponse(id, command);
    }

    @PatchMapping("/{id}")
    @RequireAdmin
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Partially update a product",
            description = "Supports deactivation via active=false. Requires the current optimistic-locking version.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Product updated")
    @ApiResponse(responseCode = "400", description = "Validation failure or inactive/missing category")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "Version conflict or duplicate slug")
    public ProductResponse patch(@PathVariable Long id, @Valid @RequestBody PatchProductCommand command) {
        return productService.patchResponse(id, command);
    }

    @DeleteMapping("/{id}")
    @RequireAdmin
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Deactivate a product",
            description = "Soft-deactivates the product so it disappears from the public catalog "
                    + "and cannot be purchased. Requires the current optimistic-locking version "
                    + "as a query parameter to prevent lost updates.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "204", description = "Product deactivated")
    @ApiResponse(responseCode = "400", description = "Missing or invalid version")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "409", description = "Version conflict")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestParam Long version) {
        productService.deactivate(id, version);
        return ResponseEntity.noContent().build();
    }
}
