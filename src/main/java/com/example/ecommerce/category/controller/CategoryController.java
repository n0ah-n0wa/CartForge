package com.example.ecommerce.category.controller;

import com.example.ecommerce.category.dto.CategoryResponse;
import com.example.ecommerce.category.dto.CreateCategoryCommand;
import com.example.ecommerce.category.dto.PatchCategoryCommand;
import com.example.ecommerce.category.dto.UpdateCategoryCommand;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.mapper.CategoryMapper;
import com.example.ecommerce.category.service.CategoryService;
import com.example.ecommerce.common.security.CurrentUserProvider;
import com.example.ecommerce.common.security.RequireAdmin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Product category catalog")
public class CategoryController {

    private final CategoryService categoryService;
    private final CategoryMapper categoryMapper;
    private final CurrentUserProvider currentUser;

    public CategoryController(
            CategoryService categoryService,
            CategoryMapper categoryMapper,
            CurrentUserProvider currentUser) {
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "List active categories", description = "Public catalog listing. Inactive categories are omitted.")
    @ApiResponse(responseCode = "200", description = "Active categories ordered by name")
    public List<CategoryResponse> list() {
        return categoryService.listActive().stream()
                .map(categoryMapper::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Retrieve a category",
            description = "Public callers receive active categories only. Administrators may retrieve inactive categories.")
    @ApiResponse(responseCode = "200", description = "Category found")
    @ApiResponse(responseCode = "404", description = "Category not found or inactive for public callers")
    public CategoryResponse get(@PathVariable Long id) {
        return categoryMapper.toResponse(categoryService.getById(id, currentUser.isAdmin()));
    }

    @PostMapping
    @RequireAdmin
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a category", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "201", description = "Category created")
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "409", description = "Duplicate name or slug")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryCommand command) {
        Category created = categoryService.create(command);
        CategoryResponse body = categoryMapper.toResponse(created);
        return ResponseEntity.created(URI.create("/api/v1/categories/" + created.getId())).body(body);
    }

    @PutMapping("/{id}")
    @RequireAdmin
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Replace a category", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Category updated")
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "404", description = "Category not found")
    @ApiResponse(responseCode = "409", description = "Duplicate name or slug")
    public CategoryResponse replace(@PathVariable Long id, @Valid @RequestBody UpdateCategoryCommand command) {
        return categoryMapper.toResponse(categoryService.update(id, command));
    }

    @PatchMapping("/{id}")
    @RequireAdmin
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Partially update a category", security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Category updated")
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "404", description = "Category not found")
    @ApiResponse(responseCode = "409", description = "Duplicate name or slug")
    public CategoryResponse patch(@PathVariable Long id, @Valid @RequestBody PatchCategoryCommand command) {
        return categoryMapper.toResponse(categoryService.patch(id, command));
    }

    @DeleteMapping("/{id}")
    @RequireAdmin
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Delete a category",
            description = "Physically removes the category when no products reference it. "
                    + "Returns 409 when products exist; deactivate with PATCH instead.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "204", description = "Category deleted")
    @ApiResponse(responseCode = "401", description = "Authentication required")
    @ApiResponse(responseCode = "403", description = "Administrator role required")
    @ApiResponse(responseCode = "404", description = "Category not found")
    @ApiResponse(responseCode = "409", description = "Category has products")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
