package com.example.ecommerce.product.mapper;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.product.dto.CreateProductCommand;
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
                new ProductCategoryResponse(category.getId(), category.getName(), category.getSlug()),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
