package com.example.ecommerce.product.mapper;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.product.dto.CreateProductCommand;
import com.example.ecommerce.product.dto.PatchProductCommand;
import com.example.ecommerce.product.dto.ProductCategoryResponse;
import com.example.ecommerce.product.dto.ProductResponse;
import com.example.ecommerce.product.dto.UpdateProductCommand;
import com.example.ecommerce.product.entity.Product;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    public Product toEntity(CreateProductCommand command, Category category) {
        return Product.create(
                command.sku(),
                command.name(),
                command.slug(),
                command.description(),
                command.price(),
                command.currency(),
                command.stockQuantity(),
                category);
    }

    public void apply(UpdateProductCommand command, Product product, Category category) {
        product.rename(command.name(), command.slug());
        product.changeDescription(command.description());
        product.changePrice(command.price(), command.currency());
        product.changeStock(command.stockQuantity());
        product.reassignCategory(category);
        if (Boolean.TRUE.equals(command.active())) {
            product.activate();
        } else {
            product.deactivate();
        }
    }

    public void applyPatch(PatchProductCommand command, Product product, Category categoryOrNull) {
        if (command.name() != null || command.slug() != null) {
            product.rename(
                    command.name() != null ? command.name() : product.getName(),
                    command.slug() != null ? command.slug() : product.getSlug());
        }
        if (command.description() != null) {
            product.changeDescription(command.description());
        }
        if (command.price() != null || command.currency() != null) {
            product.changePrice(
                    command.price() != null ? command.price() : product.getPrice(),
                    command.currency() != null ? command.currency() : product.getCurrency());
        }
        if (command.stockQuantity() != null) {
            product.changeStock(command.stockQuantity());
        }
        if (categoryOrNull != null) {
            product.reassignCategory(categoryOrNull);
        }
        if (command.active() != null) {
            if (command.active()) {
                product.activate();
            } else {
                product.deactivate();
            }
        }
    }

    /**
     * Reads the category association, so the product must be loaded with the
     * category fetched to avoid a per-product select.
     */
    public ProductResponse toResponse(Product product) {
        Category category = product.getCategory();
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getSlug(),
                product.getDescription(),
                product.getPrice(),
                product.getCurrency(),
                product.getStockQuantity(),
                product.isActive(),
                product.isPurchasable(),
                product.getVersion(),
                new ProductCategoryResponse(category.getId(), category.getName(), category.getSlug()),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
