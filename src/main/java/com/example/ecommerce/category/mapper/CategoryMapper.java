package com.example.ecommerce.category.mapper;

import com.example.ecommerce.category.dto.CategoryResponse;
import com.example.ecommerce.category.dto.CreateCategoryCommand;
import com.example.ecommerce.category.dto.PatchCategoryCommand;
import com.example.ecommerce.category.dto.UpdateCategoryCommand;
import com.example.ecommerce.category.entity.Category;
import org.springframework.stereotype.Component;

@Component
public class CategoryMapper {

    public Category toEntity(CreateCategoryCommand command) {
        return Category.create(command.name(), command.slug(), command.description());
    }

    public void apply(UpdateCategoryCommand command, Category category) {
        category.rename(command.name(), command.slug());
        category.changeDescription(command.description());
        if (Boolean.TRUE.equals(command.active())) {
            category.activate();
        } else {
            category.deactivate();
        }
    }

    public void applyPatch(PatchCategoryCommand command, Category category) {
        if (command.name() != null || command.slug() != null) {
            category.rename(
                    command.name() != null ? command.name() : category.getName(),
                    command.slug() != null ? command.slug() : category.getSlug());
        }
        if (command.description() != null) {
            category.changeDescription(command.description());
        }
        if (command.active() != null) {
            if (command.active()) {
                category.activate();
            } else {
                category.deactivate();
            }
        }
    }

    public CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.isActive(),
                category.getCreatedAt(),
                category.getUpdatedAt());
    }
}
