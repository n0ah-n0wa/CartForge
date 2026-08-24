package com.example.ecommerce.product.service;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.cache.CatalogCaches;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.pagination.PageRequests;
import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.product.dto.CreateProductCommand;
import com.example.ecommerce.product.dto.PatchProductCommand;
import com.example.ecommerce.product.dto.ProductResponse;
import com.example.ecommerce.product.dto.UpdateProductCommand;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.mapper.ProductMapper;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.repository.ProductSpecifications;
import java.util.Locale;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
     * Cached public catalog search. Values are DTOs so Redis never stores Hibernate
     * entities; PostgreSQL remains the source of truth.
     */
    @Cacheable(cacheNames = CatalogCaches.PRODUCTS, key = "#criteria.cacheKey()")
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> searchResponses(ProductSearchCriteria criteria) {
        PageResponse<Product> page = search(criteria);
        return new PageResponse<>(
                page.content().stream().map(productMapper::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages());
    }

    /**
     * Public catalog search: active products only, with optional category,
     * price range, and text filters, plus allowlisted sorting and bounded paging.
     */
    @Transactional(readOnly = true)
    public PageResponse<Product> search(ProductSearchCriteria criteria) {
        Sort resolvedSort = ProductSortSupport.ALLOWED.resolve(criteria.sort());
        Pageable pageable = PageRequests.of(
                criteria.page(),
                criteria.size(),
                properties.pagination().defaultPageSize(),
                properties.pagination().maxPageSize(),
                resolvedSort);
        Page<Product> result = productRepository.findAll(ProductSpecifications.from(criteria), pageable);
        return new PageResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /**
     * Cached product lookup for public (active-only) reads. Admin lookups that may
     * include inactive products bypass the cache so inactive data is never served
     * from a public key.
     */
    @Cacheable(
            cacheNames = CatalogCaches.PRODUCT,
            key = "#id",
            condition = "!#includeInactive")
    @Transactional(readOnly = true)
    public ProductResponse getResponse(Long id, boolean includeInactive) {
        return productMapper.toResponse(getById(id, includeInactive));
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

    @CacheEvict(cacheNames = CatalogCaches.PRODUCTS, allEntries = true)
    public Product create(CreateProductCommand command) {
        Category category = requireActiveCategory(command.categoryId());
        ensureUniqueSku(command.sku(), null);
        ensureUniqueSlug(command.slug(), null);
        try {
            Product created = productRepository.saveAndFlush(productMapper.toEntity(command, category));
            // Reload with the category graph so callers can map outside this transaction.
            return productRepository.findWithCategoryById(created.getId()).orElse(created);
        } catch (DataIntegrityViolationException duplicate) {
            throw translateDuplicate(duplicate, command.sku(), command.slug());
        }
    }

    /**
     * Proxy entry used by the REST layer. Cache eviction is declared here
     * because {@link #create} is invoked as {@code this.create} and would
     * otherwise skip Spring cache advice.
     */
    @CacheEvict(cacheNames = CatalogCaches.PRODUCTS, allEntries = true)
    public ProductResponse createResponse(CreateProductCommand command) {
        return productMapper.toResponse(create(command));
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CatalogCaches.PRODUCT, key = "#id"),
            @CacheEvict(cacheNames = CatalogCaches.PRODUCTS, allEntries = true)
    })
    public Product update(Long id, UpdateProductCommand command) {
        Product product = requireWithCategory(id);
        assertVersion(product, command.version());
        Category category = requireActiveCategory(command.categoryId());
        ensureUniqueSlug(command.slug(), id);
        productMapper.apply(command, product, category);
        return flushProduct(product, product.getSku(), command.slug());
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CatalogCaches.PRODUCT, key = "#id"),
            @CacheEvict(cacheNames = CatalogCaches.PRODUCTS, allEntries = true)
    })
    public ProductResponse updateResponse(Long id, UpdateProductCommand command) {
        return productMapper.toResponse(update(id, command));
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CatalogCaches.PRODUCT, key = "#id"),
            @CacheEvict(cacheNames = CatalogCaches.PRODUCTS, allEntries = true)
    })
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

    @Caching(evict = {
            @CacheEvict(cacheNames = CatalogCaches.PRODUCT, key = "#id"),
            @CacheEvict(cacheNames = CatalogCaches.PRODUCTS, allEntries = true)
    })
    public ProductResponse patchResponse(Long id, PatchProductCommand command) {
        return productMapper.toResponse(patch(id, command));
    }

    /**
     * Soft-deactivates the product. Historical order lines keep their product
     * reference, so physical deletion is not used for the public DELETE API.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CatalogCaches.PRODUCT, key = "#id"),
            @CacheEvict(cacheNames = CatalogCaches.PRODUCTS, allEntries = true)
    })
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
