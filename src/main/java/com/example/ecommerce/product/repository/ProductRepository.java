package com.example.ecommerce.product.repository;

import com.example.ecommerce.product.entity.Product;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The {@code category} association is lazy. Catalog finders declare an entity
 * graph so a page of products still costs one query when the category is rendered.
 */
public interface ProductRepository
        extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product>, ProductLockingQueries {

    Optional<Product> findBySku(String sku);

    Optional<Product> findBySlug(String slug);

    boolean existsBySku(String sku);

    boolean existsBySlug(String slug);

    boolean existsBySkuAndIdNot(String sku, Long id);

    boolean existsBySlugAndIdNot(String slug, Long id);

    long countByCategoryId(Long categoryId);

    /**
     * Bypasses the persistence-context copy so inventory checks see the latest
     * committed {@code stock_quantity} (and this transaction's own writes).
     */
    @Query(value = "select stock_quantity from products where id = :id", nativeQuery = true)
    Optional<Integer> findStockQuantityById(@Param("id") Long id);

    /**
     * Category reassignment pages through this finder so large categories are not
     * loaded into memory in one shot. Callers must re-request page 0 after each
     * batch because moved rows leave the source category.
     */
    Page<Product> findByCategoryId(Long categoryId, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Optional<Product> findWithCategoryById(Long id);

    @EntityGraph(attributePaths = "category")
    Optional<Product> findWithCategoryBySlug(String slug);

    /**
     * Redeclared so catalog searches always fetch the category in the same select
     * instead of falling back to the graph-less {@link JpaSpecificationExecutor}
     * default.
     */
    @Override
    @EntityGraph(attributePaths = "category")
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);
}
