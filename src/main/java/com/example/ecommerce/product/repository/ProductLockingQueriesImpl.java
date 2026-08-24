package com.example.ecommerce.product.repository;

import com.example.ecommerce.product.entity.Product;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;

public class ProductLockingQueriesImpl implements ProductLockingQueries {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<Product> findByIdForUpdate(Long id) {
        List<Long> ids = entityManager
                .createQuery("select p.id from Product p where p.id = :id", Long.class)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setParameter("id", id)
                .getResultList();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        Product product = entityManager.find(Product.class, ids.getFirst());
        entityManager.refresh(product);
        return Optional.of(product);
    }
}
