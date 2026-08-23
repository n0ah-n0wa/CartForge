package com.example.ecommerce.product.service;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.product.dto.CreateProductCommand;
import com.example.ecommerce.product.dto.PatchProductCommand;
import com.example.ecommerce.product.dto.UpdateProductCommand;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.mapper.ProductMapper;
import com.example.ecommerce.product.repository.ProductRepository;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;
    private final ApplicationProperties properties;

    public ProductService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            ProductMapper productMapper,
            ApplicationProperties properties) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productMapper = productMapper;
        this.properties = properties;
    }

    /**
     * Public listing foundation: active products only, paged, ordered by name.
     * Advanced search and filtering are intentionally deferred.
     */
    @Transactional(readOnly = true)
    public PageResponse<Product> listActive(Integer page, Integer size) {
        int pageNumber = page == null || page < 0 ? 0 : page;
        int pageSize = resolvePageSize(size);
        Page<Product> result = productRepository.findByActiveTrue(
                PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.ASC, "name")));
        return new PageResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Product getById(Long id, boolean includeInactive) {
        Product product = productRepository.findWithCategoryById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        if (!product.isActive() && !includeInactive) {
            throw new ProductNotFoundException(id);
        }
        return product;
    }

    public Product create(CreateProductCommand command) {
        Category category = requireActiveCategory(command.categoryId());
        ensureUniqueSku(command.sku(), null);
        ensureUniqueSlug(command.slug(), null);
        try {
            Product created = productRepository.saveAndFlush(productMapper.toEntity(command, category));
            // Reload with the category graph so controllers can map outside this transaction.
            return productRepository.findWithCategoryById(created.getId()).orElse(created);
        } catch (DataIntegrityViolationException duplicate) {
            throw translateDuplicate(duplicate, command.sku(), command.slug());
        }
    }

    public Product update(Long id, UpdateProductCommand command) {
        Product product = requireWithCategory(id);
        assertVersion(product, command.version());
        Category category = requireActiveCategory(command.categoryId());
        ensureUniqueSlug(command.slug(), id);
        productMapper.apply(command, product, category);
        return flushProduct(product, product.getSku(), command.slug());
    }

    public Product patch(Long id, PatchProductCommand command) {
        Product product = requireWithCategory(id);
        assertVersion(product, command.version());
        Category category = command.categoryId() == null
                ? null
                : requireActiveCategory(command.categoryId());
        if (command.slug() != null) {
            ensureUniqueSlug(command.slug(), id);
        }
        productMapper.applyPatch(command, product, category);
        String slug = command.slug() != null ? command.slug() : product.getSlug();
        return flushProduct(product, product.getSku(), slug);
    }

    /**
     * Soft-deactivates the product. Historical order lines keep their product
     * reference, so physical deletion is not used for the public DELETE API.
     */
    public Product deactivate(Long id, Long version) {
        Product product = requireWithCategory(id);
        assertVersion(product, version);
        product.deactivate();
        return flushProduct(product, product.getSku(), product.getSlug());
    }

    @Transactional(readOnly = true)
    public Product require(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    private Product requireWithCategory(Long id) {
        return productRepository.findWithCategoryById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    private Category requireActiveCategory(Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new InvalidProductCategoryException(categoryId));
        if (!category.isActive()) {
            throw new InvalidProductCategoryException(categoryId);
        }
        return category;
    }

    private void ensureUniqueSku(String sku, Long excludeId) {
        String normalized = sku.trim().toUpperCase(Locale.ROOT);
        boolean duplicate = excludeId == null
                ? productRepository.existsBySku(normalized)
                : productRepository.existsBySkuAndIdNot(normalized, excludeId);
        if (duplicate) {
            throw new DuplicateProductException(DuplicateProductException.Field.SKU, normalized);
        }
    }

    private void ensureUniqueSlug(String slug, Long excludeId) {
        String normalized = slug.trim().toLowerCase(Locale.ROOT);
        boolean duplicate = excludeId == null
                ? productRepository.existsBySlug(normalized)
                : productRepository.existsBySlugAndIdNot(normalized, excludeId);
        if (duplicate) {
            throw new DuplicateProductException(DuplicateProductException.Field.SLUG, normalized);
        }
    }

    private static void assertVersion(Product product, Long expectedVersion) {
        if (expectedVersion == null || !expectedVersion.equals(product.getVersion())) {
            throw new ProductVersionConflictException(
                    product.getId(), expectedVersion, product.getVersion());
        }
    }

    private Product flushProduct(Product product, String sku, String slug) {
        try {
            return productRepository.saveAndFlush(product);
        } catch (OptimisticLockingFailureException conflict) {
            throw new ProductVersionConflictException(product.getId(), null, product.getVersion());
        } catch (DataIntegrityViolationException duplicate) {
            throw translateDuplicate(duplicate, sku, slug);
        }
    }

    private int resolvePageSize(Integer size) {
        int defaultSize = properties.pagination().defaultPageSize();
        int maxSize = properties.pagination().maxPageSize();
        if (size == null || size < 1) {
            return defaultSize;
        }
        return Math.min(size, maxSize);
    }

    private static DuplicateProductException translateDuplicate(
            DataIntegrityViolationException exception,
            String sku,
            String slug) {
        String message = exception.getMessage();
        if (message != null && message.contains("uq_products_slug")) {
            return new DuplicateProductException(DuplicateProductException.Field.SLUG, slug);
        }
        return new DuplicateProductException(DuplicateProductException.Field.SKU, sku);
    }
}
