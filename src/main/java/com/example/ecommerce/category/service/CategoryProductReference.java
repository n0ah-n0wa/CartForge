package com.example.ecommerce.category.service;

/**
 * Product-side lookups used when deleting or reassigning a category. The product
 * module supplies the adapter so the category module does not depend on it.
 */
public interface CategoryProductReference {

    long countByCategoryId(Long categoryId);

    int reassign(Long sourceCategoryId, Long targetCategoryId);
}
