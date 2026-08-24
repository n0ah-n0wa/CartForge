package com.example.ecommerce.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.repository.CategoryRepository;
import com.example.ecommerce.common.config.ApplicationProperties;
import com.example.ecommerce.common.pagination.InvalidSortException;
import com.example.ecommerce.common.pagination.PageResponse;
import com.example.ecommerce.common.persistence.CurrencyCode;
import com.example.ecommerce.product.dto.CreateProductCommand;
import com.example.ecommerce.product.dto.PatchProductCommand;
import com.example.ecommerce.product.dto.UpdateProductCommand;
import com.example.ecommerce.product.entity.Product;
import com.example.ecommerce.product.mapper.ProductMapper;
import com.example.ecommerce.product.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    private final ProductMapper productMapper = new ProductMapper();

    private ProductService productService;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties(
                new ApplicationProperties.Jwt("test-only-jwt-secret-not-for-production-use", 3_600_000L),
                new ApplicationProperties.Cors(List.of("http://localhost")),
                new ApplicationProperties.Pagination(20, 100),
                ApplicationProperties.RateLimit.defaults());
        productService = new ProductService(productRepository, categoryRepository, productMapper, properties);
    }

    @Test
    void searchReturnsPagedActiveProductsWithDefaultSort() {
        Category category = Category.create("Books", "books", null);
        Product product = product(category, 0L);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(productRepository.findAll(any(Specification.class), pageableCaptor.capture())).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            return new PageImpl<>(List.of(product), pageable, 1);
        });

        PageResponse<Product> page = productService.search(ProductSearchCriteria.of(
                null, null, null, null, 0, 10, null));

        assertThat(page.content()).containsExactly(product);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("name").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void searchAppliesAllowlistedSortAndCapsPageSize() {
        when(productRepository.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(1);
            return new PageImpl<>(List.of(), pageable, 0);
        });
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        productService.search(ProductSearchCriteria.of(
                null, null, null, null, 1, 500, List.of("price,desc")));

        verify(productRepository).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(100);
        assertThat(pageable.getSort().getOrderFor("price").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void searchRejectsDisallowedSortFields() {
        assertThatThrownBy(() -> productService.search(ProductSearchCriteria.of(
                        null, null, null, null, 0, 10, List.of("version"))))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("version");
        verify(productRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void searchCriteriaRejectsInvalidPriceRangeAndOversizedSearch() {
        assertThatThrownBy(() -> ProductSearchCriteria.of(
                        null, new BigDecimal("20.00"), new BigDecimal("10.00"), null, 0, 10, null))
                .isInstanceOf(InvalidProductQueryException.class)
                .hasMessageContaining("minPrice");

        assertThatThrownBy(() -> ProductSearchCriteria.of(
                        null, new BigDecimal("-1"), null, null, 0, 10, null))
                .isInstanceOf(InvalidProductQueryException.class)
                .hasMessageContaining("minPrice");

        assertThatThrownBy(() -> ProductSearchCriteria.of(
                        null, null, null, "x".repeat(ProductSearchCriteria.MAX_SEARCH_LENGTH + 1), 0, 10, null))
                .isInstanceOf(InvalidProductQueryException.class)
                .hasMessageContaining("search");
    }

    @Test
    void getByIdHidesInactiveProductsFromPublicCallers() {
        Product product = product(Category.create("Books", "books", null), 0L);
        product.deactivate();
        when(productRepository.findWithCategoryById(8L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.getById(8L, false))
                .isInstanceOf(ProductNotFoundException.class);
        assertThat(productService.getById(8L, true)).isSameAs(product);
    }

    @Test
    void createRejectsMissingCategory() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.create(createCommand(99L)))
                .isInstanceOf(InvalidProductCategoryException.class);
        verify(productRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsInactiveCategory() {
        Category inactive = Category.create("Archived", "archived", null);
        inactive.deactivate();
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> productService.create(createCommand(1L)))
                .isInstanceOf(InvalidProductCategoryException.class);
    }

    @Test
    void createRejectsDuplicateSku() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(Category.create("Books", "books", null)));
        when(productRepository.existsBySku("KB-001")).thenReturn(true);

        assertThatThrownBy(() -> productService.create(createCommand(1L)))
                .isInstanceOf(DuplicateProductException.class)
                .extracting(ex -> ((DuplicateProductException) ex).code())
                .isEqualTo("DUPLICATE_PRODUCT_SKU");
    }

    @Test
    void updateRejectsStaleVersion() {
        Product product = product(Category.create("Books", "books", null), 0L);
        when(productRepository.findWithCategoryById(8L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.update(
                        8L,
                        new UpdateProductCommand(
                                5L,
                                "Keyboard",
                                "keyboard",
                                null,
                                new BigDecimal("10.00"),
                                CurrencyCode.EUR,
                                1,
                                1L,
                                true)))
                .isInstanceOf(ProductVersionConflictException.class);
    }

    @Test
    void patchCanDeactivateAProduct() {
        Product product = product(Category.create("Books", "books", null), 0L);
        when(productRepository.findWithCategoryById(8L)).thenReturn(Optional.of(product));
        when(productRepository.saveAndFlush(product)).thenReturn(product);

        Product patched = productService.patch(
                8L, new PatchProductCommand(0L, null, null, null, null, null, null, null, false));

        assertThat(patched.isActive()).isFalse();
        assertThat(patched.isPurchasable()).isFalse();
    }

    @Test
    void deactivateRequiresMatchingVersion() {
        Product product = product(Category.create("Books", "books", null), 0L);
        when(productRepository.findWithCategoryById(8L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.deactivate(8L, 3L))
                .isInstanceOf(ProductVersionConflictException.class);
        verify(productRepository, never()).saveAndFlush(any());
    }

    private static Product product(Category category, long version) {
        Product product = Product.create(
                "KB-001", "Keyboard", "keyboard", null, new BigDecimal("10.00"), null, 1, category);
        ReflectionTestUtils.setField(product, "version", version);
        return product;
    }

    private static CreateProductCommand createCommand(Long categoryId) {
        return new CreateProductCommand(
                "KB-001",
                "Keyboard",
                "keyboard",
                null,
                new BigDecimal("10.00"),
                null,
                1,
                categoryId);
    }
}
