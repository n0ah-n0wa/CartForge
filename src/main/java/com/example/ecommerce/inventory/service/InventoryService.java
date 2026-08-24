package com.example.ecommerce.inventory.service;

import com.example.ecommerce.inventory.dto.StockLevel;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.service.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal inventory operations over {@link Product#getStockQuantity()}.
 *
 * <p>Cart lines do not reserve stock; callers that mutate inventory (checkout,
 * cancellation, admin restock) must go through this service so non-negativity,
 * transactions, and locking stay consistent. Mutations take a product row lock
 * then flush with optimistic {@code version} so concurrent catalog edits still
 * surface as conflicts when appropriate.
 */
@Service
@Transactional
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final ProductRepository productRepository;

    public InventoryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Increases available stock (e.g. administrative restock).
     */
    public StockLevel increaseStock(Long productId, int quantity) {
        requirePositiveQuantity(quantity);
        Product product = requireProductForMutation(productId);
        product.increaseStock(quantity);
        return persist(product);
    }

    /**
     * Decreases available stock (e.g. checkout). Never allows stock to go below zero.
     */
    public StockLevel decreaseStock(Long productId, int quantity) {
        requirePositiveQuantity(quantity);
        Product product = requireProductForMutation(productId);
        ensureSufficientStock(product, quantity);
        product.decreaseStock(quantity);
        return persist(product);
    }

    /**
     * Restores stock previously decremented (e.g. order cancellation).
     * Semantically distinct from restock; currently the same stock arithmetic.
     */
    public StockLevel restoreStock(Long productId, int quantity) {
        requirePositiveQuantity(quantity);
        Product product = requireProductForMutation(productId);
        product.increaseStock(quantity);
        return persist(product);
    }

    /**
     * Validates that {@code quantity} units are currently available without mutating stock.
     *
     * @throws InsufficientStockException when available stock is too low
     */
    @Transactional(readOnly = true)
    public void validateAvailability(Long productId, int quantity) {
        requirePositiveQuantity(quantity);
        Product product = requireProduct(productId);
        int available = committedStock(productId);
        if (quantity > available) {
            log.warn(
                    "event=insufficient_stock productId={} available={} requested={}",
                    product.getId(),
                    available,
                    quantity);
            throw new InsufficientStockException(product.getId(), available, quantity);
        }
    }

    /**
     * Read-only stock snapshot for a product.
     */
    @Transactional(readOnly = true)
    public StockLevel getStockLevel(Long productId) {
        Product product = requireProduct(productId);
        return new StockLevel(product.getId(), committedStock(productId), product.getVersion());
    }

    private StockLevel persist(Product product) {
        try {
            return toStockLevel(productRepository.saveAndFlush(product));
        } catch (OptimisticLockingFailureException conflict) {
            log.warn("event=inventory_conflict productId={}", product.getId());
            throw new InventoryConflictException(product.getId());
        }
    }

    private Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    /**
     * Locks the product row and refreshes the persistence-context copy so stock
     * and {@code version} match the database. Cart checkout may already have
     * loaded the product; without a refresh a later flush would fail optimistic
     * locking even after waiting for the row lock.
     */
    private Product requireProductForMutation(Long productId) {
        return productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    private int committedStock(Long productId) {
        return productRepository.findStockQuantityById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    private static void ensureSufficientStock(Product product, int requestedQuantity) {
        int available = product.getStockQuantity();
        if (requestedQuantity > available) {
            log.warn(
                    "event=insufficient_stock productId={} available={} requested={}",
                    product.getId(),
                    available,
                    requestedQuantity);
            throw new InsufficientStockException(product.getId(), available, requestedQuantity);
        }
    }

    private static void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new InvalidInventoryQuantityException("quantity must be greater than zero");
        }
    }

    private static StockLevel toStockLevel(Product product) {
        return new StockLevel(product.getId(), product.getStockQuantity(), product.getVersion());
    }
}
