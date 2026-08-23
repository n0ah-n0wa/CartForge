package com.example.ecommerce.product.repository;

import com.example.ecommerce.product.entity.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The {@code category} association is lazy. Catalog finders declare an entity
 * graph so a page of products still costs one query when the category is rendered.
 */
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findBySku(String sku);

    Optional<Product> findBySlug(String slug);

    boolean existsBySku(String sku);

    boolean existsBySlug(String slug);

    boolean existsBySkuAndIdNot(String sku, Long id);

    boolean existsBySlugAndIdNot(String slug, Long id);

    long countByCategoryId(Long categoryId);

    List<Product> findByCategoryId(Long categoryId);

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
