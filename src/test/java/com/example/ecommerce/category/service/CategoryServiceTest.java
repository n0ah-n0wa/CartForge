package com.example.ecommerce.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ecommerce.category.dto.CreateCategoryCommand;
import com.example.ecommerce.category.dto.PatchCategoryCommand;
import com.example.ecommerce.category.dto.UpdateCategoryCommand;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.mapper.CategoryMapper;
import com.example.ecommerce.category.repository.CategoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    private final CategoryMapper categoryMapper = new CategoryMapper();

    @Mock
    private CategoryProductReference productReference;

    private CategoryService categoryService;

    @BeforeEach
    void wireMapper() {
        categoryService = new CategoryService(categoryRepository, categoryMapper, productReference);
    }

    @Test
    void createRejectsDuplicateName() {
        when(categoryRepository.existsByName("Books")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(new CreateCategoryCommand("Books", "paper", null)))
                .isInstanceOf(DuplicateCategoryException.class);
        verify(categoryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateRejectsDuplicateSlugForAnotherCategory() {
        Category category = Category.create("Books", "books", null);
        when(categoryRepository.findById(8L)).thenReturn(java.util.Optional.of(category));
        when(categoryRepository.existsBySlugAndIdNot("media", 8L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.update(
                        8L, new UpdateCategoryCommand("Books", "media", null, true)))
                .isInstanceOf(DuplicateCategoryException.class);
    }

    @Test
    void patchUpdatesOnlyProvidedFields() {
        Category category = Category.create("Books", "books", "Printed");
        when(categoryRepository.findById(8L)).thenReturn(java.util.Optional.of(category));
        when(categoryRepository.saveAndFlush(category)).thenReturn(category);

        Category patched = categoryService.patch(8L, new PatchCategoryCommand(null, null, "Updated", false));

        assertThat(patched.isActive()).isFalse();
        assertThat(patched.getDescription()).isEqualTo("Updated");
        assertThat(patched.getName()).isEqualTo("Books");
    }

    @Test
    void listActiveDelegatesToRepository() {
        Category books = Category.create("Books", "books", null);
        when(categoryRepository.findByActiveTrueOrderByNameAsc()).thenReturn(java.util.List.of(books));

        assertThat(categoryService.listActive()).containsExactly(books);
    }

    @Test
    void getByIdHidesInactiveCategoriesFromPublicCallers() {
        Category hidden = Category.create("Archived", "archived", null);
        hidden.deactivate();
        when(categoryRepository.findById(8L)).thenReturn(java.util.Optional.of(hidden));

        assertThatThrownBy(() -> categoryService.getById(8L, false))
                .isInstanceOf(CategoryNotFoundException.class);
        assertThat(categoryService.getById(8L, true)).isSameAs(hidden);
    }

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
