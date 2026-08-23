package com.example.ecommerce.category.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ecommerce.category.dto.CategoryResponse;
import com.example.ecommerce.category.dto.CreateCategoryCommand;
import com.example.ecommerce.category.dto.PatchCategoryCommand;
import com.example.ecommerce.category.dto.UpdateCategoryCommand;
import com.example.ecommerce.category.entity.Category;
import org.junit.jupiter.api.Test;

class CategoryMapperTest {

    private final CategoryMapper mapper = new CategoryMapper();

    @Test
    void mapsCreateCommandToActiveCategory() {
        Category category = mapper.toEntity(new CreateCategoryCommand(
                "  Books  ",
                "Books",
                "  Printed titles  "));

        assertThat(category.getName()).isEqualTo("Books");
        assertThat(category.getSlug()).isEqualTo("books");
        assertThat(category.getDescription()).isEqualTo("Printed titles");
        assertThat(category.isActive()).isTrue();
    }

    @Test
    void appliesUpdateAndDeactivation() {
        Category category = Category.create("Books", "books", "Printed titles");

        mapper.apply(new UpdateCategoryCommand("Electronics", "electronics", null, false), category);

        assertThat(category.getName()).isEqualTo("Electronics");
        assertThat(category.getSlug()).isEqualTo("electronics");
        assertThat(category.getDescription()).isNull();
        assertThat(category.isActive()).isFalse();
    }

    @Test
    void appliesPatchToSelectedFields() {
        Category category = Category.create("Books", "books", "Printed titles");

        mapper.applyPatch(new PatchCategoryCommand("Media", null, null, null), category);

        assertThat(category.getName()).isEqualTo("Media");
        assertThat(category.getSlug()).isEqualTo("books");
        assertThat(category.getDescription()).isEqualTo("Printed titles");
        assertThat(category.isActive()).isTrue();
    }

    @Test
    void mapsEntityToResponse() {
        Category category = Category.create("Books", "books", null);

        CategoryResponse response = mapper.toResponse(category);

        assertThat(response.name()).isEqualTo("Books");
        assertThat(response.slug()).isEqualTo("books");
        assertThat(response.description()).isNull();
        assertThat(response.active()).isTrue();
    }
}
