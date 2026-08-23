package com.example.ecommerce.product.service;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.category.service.CategoryProductReference;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product-side adapter for the port the category module uses to decide whether a
 * category may be deleted. Reassignment walks the managed entities of a single
 * category so optimistic locking and {@code updated_at} still apply; a bulk
 * update would silently skip both.
 */
@Component
public class ProductCategoryReference implements CategoryProductReference {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductCategoryReference(
            ProductRepository productRepository,
            CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public long countByCategoryId(Long categoryId) {
        return productRepository.countByCategoryId(categoryId);
    }

    @Override
    @Transactional
    public int reassign(Long sourceCategoryId, Long targetCategoryId) {
        Category target = categoryRepository.findById(targetCategoryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Reassignment target category does not exist: " + targetCategoryId));

        List<Product> products = productRepository.findByCategoryId(sourceCategoryId);
        for (Product product : products) {
            product.reassignCategory(target);
        }
        productRepository.saveAll(products);
        return products.size();
    }
}
