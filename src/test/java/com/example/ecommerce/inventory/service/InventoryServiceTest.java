package com.example.ecommerce.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.inventory.dto.StockLevel;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.repository.ProductRepository;
import com.example.ecommerce.product.service.ProductNotFoundException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final long PRODUCT_ID = 42L;
    private static final Category CATEGORY = Category.create("Books", "books", null);

    @Mock
    private ProductRepository productRepository;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(productRepository);
    }

    @Test
    void increaseStockAddsToAvailableQuantity() {
        Product product = readyProductForMutation(5);
        when(productRepository.saveAndFlush(product)).thenAnswer(invocation -> invocation.getArgument(0));

        StockLevel level = inventoryService.increaseStock(PRODUCT_ID, 3);

        assertThat(product.getStockQuantity()).isEqualTo(8);
        assertThat(level.productId()).isEqualTo(PRODUCT_ID);
        assertThat(level.stockQuantity()).isEqualTo(8);
        verify(productRepository).saveAndFlush(product);
    }

    @Test
    void decreaseStockSubtractsWhenSufficient() {
        Product product = readyProductForMutation(5);
        when(productRepository.saveAndFlush(product)).thenAnswer(invocation -> invocation.getArgument(0));

        StockLevel level = inventoryService.decreaseStock(PRODUCT_ID, 2);

        assertThat(level.stockQuantity()).isEqualTo(3);
        assertThat(product.getStockQuantity()).isEqualTo(3);
    }

    @Test
    void decreaseStockRejectsWhenInsufficient() {
        Product product = readyProductForMutation(2);

        assertThatThrownBy(() -> inventoryService.decreaseStock(PRODUCT_ID, 3))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(ex -> {
                    InsufficientStockException stock = (InsufficientStockException) ex;
                    assertThat(stock.getProductId()).isEqualTo(PRODUCT_ID);
                    assertThat(stock.getAvailable()).isEqualTo(2);
                    assertThat(stock.getRequested()).isEqualTo(3);
                });

        assertThat(product.getStockQuantity()).isEqualTo(2);
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void decreaseStockToZeroIsAllowed() {
        Product product = readyProductForMutation(2);
        when(productRepository.saveAndFlush(product)).thenAnswer(invocation -> invocation.getArgument(0));

        StockLevel level = inventoryService.decreaseStock(PRODUCT_ID, 2);

        assertThat(level.stockQuantity()).isZero();
        assertThat(product.isPurchasable()).isFalse();
    }

    @Test
    void restoreStockIncreasesQuantity() {
        Product product = readyProductForMutation(1);
        when(productRepository.saveAndFlush(product)).thenAnswer(invocation -> invocation.getArgument(0));

        StockLevel level = inventoryService.restoreStock(PRODUCT_ID, 4);

        assertThat(level.stockQuantity()).isEqualTo(5);
    }

    @Test
    void validateAvailabilitySucceedsWhenStockCoversRequest() {
        readyProductForRead(5);

        inventoryService.validateAvailability(PRODUCT_ID, 5);

        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void validateAvailabilityFailsWhenStockIsShort() {
        readyProductForRead(1);

        assertThatThrownBy(() -> inventoryService.validateAvailability(PRODUCT_ID, 2))
                .isInstanceOf(InsufficientStockException.class);
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void validateAvailabilityUsesCommittedStockWhenPersistenceContextIsStale() {
        // Entity in the PC reports 1 unit, but the dedicated stock query sees 5.
        Product stale = productWithStock(1);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(stale));
        when(productRepository.findStockQuantityById(PRODUCT_ID)).thenReturn(Optional.of(5));

        inventoryService.validateAvailability(PRODUCT_ID, 5);

        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void validateAvailabilityRejectsUsingCommittedStockWhenPersistenceContextIsStale() {
        // Entity in the PC reports plenty; committed stock is already zero.
        Product stale = productWithStock(10);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(stale));
        when(productRepository.findStockQuantityById(PRODUCT_ID)).thenReturn(Optional.of(0));

        assertThatThrownBy(() -> inventoryService.validateAvailability(PRODUCT_ID, 1))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(ex -> assertThat(((InsufficientStockException) ex).getAvailable()).isZero());

        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsNonPositiveQuantitiesForAllMutationsAndValidation() {
        assertThatThrownBy(() -> inventoryService.increaseStock(PRODUCT_ID, 0))
                .isInstanceOf(InvalidInventoryQuantityException.class);
        assertThatThrownBy(() -> inventoryService.decreaseStock(PRODUCT_ID, -1))
                .isInstanceOf(InvalidInventoryQuantityException.class);
        assertThatThrownBy(() -> inventoryService.restoreStock(PRODUCT_ID, 0))
                .isInstanceOf(InvalidInventoryQuantityException.class);
        assertThatThrownBy(() -> inventoryService.validateAvailability(PRODUCT_ID, 0))
                .isInstanceOf(InvalidInventoryQuantityException.class);

        verify(productRepository, never()).findById(any());
        verify(productRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void missingProductIsNotFound() {
        when(productRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.empty());
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.increaseStock(PRODUCT_ID, 1))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> inventoryService.getStockLevel(PRODUCT_ID))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void optimisticLockFailureBecomesInventoryConflict() {
        Product product = readyProductForMutation(5);
        when(productRepository.saveAndFlush(product))
                .thenThrow(new OptimisticLockingFailureException("stale version"));

        assertThatThrownBy(() -> inventoryService.decreaseStock(PRODUCT_ID, 1))
                .isInstanceOf(InventoryConflictException.class)
                .satisfies(ex -> assertThat(((InventoryConflictException) ex).getProductId()).isEqualTo(PRODUCT_ID));
    }

    @Test
    void getStockLevelReturnsCommittedStockNotStaleEntityQuantity() {
        Product stale = productWithStock(1);
        ReflectionTestUtils.setField(stale, "version", 3L);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(stale));
        when(productRepository.findStockQuantityById(PRODUCT_ID)).thenReturn(Optional.of(7));

        StockLevel level = inventoryService.getStockLevel(PRODUCT_ID);

        assertThat(level).isEqualTo(new StockLevel(PRODUCT_ID, 7, 3L));
        verify(productRepository, never()).saveAndFlush(any());
    }

    private Product readyProductForMutation(int stock) {
        Product product = productWithStock(stock);
        when(productRepository.findByIdForUpdate(PRODUCT_ID)).thenReturn(Optional.of(product));
        return product;
    }

    private Product readyProductForRead(int stock) {
        Product product = productWithStock(stock);
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(productRepository.findStockQuantityById(PRODUCT_ID)).thenReturn(Optional.of(stock));
        return product;
    }

    private static Product productWithStock(int stock) {
        Product product = Product.create(
                "KB-001", "Keyboard", "keyboard", null, new BigDecimal("49.50"), null, stock, CATEGORY);
        ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
        ReflectionTestUtils.setField(product, "version", 0L);
        return product;
    }
}
