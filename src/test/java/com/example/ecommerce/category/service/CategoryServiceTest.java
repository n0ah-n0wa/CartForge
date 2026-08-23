package com.example.ecommerce.category.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.mapper.CategoryMapper;
import com.example.ecommerce.category.repository.CategoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private CategoryProductReference productReference;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    void deleteIsRejectedWhenProductsExist() {
        Category category = Category.create("Books", "books", null);
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(category));
        when(productReference.countByCategoryId(8L)).thenReturn(2L);

        assertThatThrownBy(() -> categoryService.delete(8L))
                .isInstanceOf(CategoryInUseException.class)
                .hasMessageContaining("8");
        verify(categoryRepository, never()).delete(category);
    }

    @Test
    void deleteRemovesCategoryWhenNoProductsExist() {
        Category category = Category.create("Books", "books", null);
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(category));
        when(productReference.countByCategoryId(8L)).thenReturn(0L);

        categoryService.delete(8L);

        verify(categoryRepository).delete(category);
    }

    @Test
    void reassignAndDeleteRequiresDistinctTarget() {
        assertThatThrownBy(() -> categoryService.reassignAndDelete(3L, 3L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reassignAndDeleteFailsWhenTargetIsMissing() {
        Category source = Category.create("Books", "books", null);
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(source));
        when(categoryRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.reassignAndDelete(3L, 9L))
                .isInstanceOf(CategoryNotFoundException.class);
        verify(productReference, never()).reassign(3L, 9L);
        verify(categoryRepository, never()).delete(source);
    }
}
