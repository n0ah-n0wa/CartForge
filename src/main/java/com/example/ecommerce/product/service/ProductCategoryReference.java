package com.example.ecommerce.product.service;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.category.service.CategoryProductReference;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product-side adapter for the port the category module uses to decide whether a
 * category may be deleted. Reassignment walks managed entities in bounded pages
 * so optimistic locking and {@code updated_at} still apply without loading an
 * entire category into memory; a bulk SQL update would silently skip both.
 */
@Component
public class ProductCategoryReference implements CategoryProductReference {

    static final int DEFAULT_BATCH_SIZE = 100;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final int batchSize;

    @Autowired
    public ProductCategoryReference(
            ProductRepository productRepository,
            CategoryRepository categoryRepository) {
        this(productRepository, categoryRepository, DEFAULT_BATCH_SIZE);
    }

    /** For tests that assert multi-batch reassignment without loading hundreds of rows. */
    public ProductCategoryReference(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            int batchSize) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.batchSize = batchSize < 1 ? DEFAULT_BATCH_SIZE : batchSize;
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

        int moved = 0;
        PageRequest page = PageRequest.of(0, batchSize, Sort.by(Sort.Direction.ASC, "id"));
        Page<Product> batch;
        do {
            // Always page 0: after save, reassigned rows no longer match sourceCategoryId.
            batch = productRepository.findByCategoryId(sourceCategoryId, page);
            for (Product product : batch) {
                product.reassignCategory(target);
            }
            productRepository.saveAll(batch.getContent());
            productRepository.flush();
            moved += batch.getNumberOfElements();
        } while (batch.hasContent());

        return moved;
    }
}
