package com.example.ecommerce.category.service;

import com.example.ecommerce.category.dto.CreateCategoryCommand;
import com.example.ecommerce.category.dto.PatchCategoryCommand;
import com.example.ecommerce.category.dto.UpdateCategoryCommand;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.mapper.CategoryMapper;
import com.example.ecommerce.category.repository.CategoryRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;
    private final CategoryProductReference productReference;

    public CategoryService(
            CategoryRepository categoryRepository,
            CategoryMapper categoryMapper,
            CategoryProductReference productReference) {
        this.categoryRepository = categoryRepository;
        this.categoryMapper = categoryMapper;
        this.productReference = productReference;
    }

    @Transactional(readOnly = true)
    public List<Category> listActive() {
        return categoryRepository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Category getById(Long id, boolean includeInactive) {
        Category category = require(id);
        if (!category.isActive() && !includeInactive) {
            throw new CategoryNotFoundException(id);
        }
        return category;
    }

    public Category create(CreateCategoryCommand command) {
        ensureUniqueIdentity(command.name(), command.slug(), null);
        try {
            return categoryRepository.save(categoryMapper.toEntity(command));
        } catch (DataIntegrityViolationException duplicate) {
            throw translateDuplicate(duplicate, command.name(), command.slug());
        }
    }

    public Category update(Long id, UpdateCategoryCommand command) {
        Category category = require(id);
        ensureUniqueIdentity(command.name(), command.slug(), id);
        categoryMapper.apply(command, category);
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException duplicate) {
            throw translateDuplicate(duplicate, command.name(), command.slug());
        }
    }

    public Category patch(Long id, PatchCategoryCommand command) {
        Category category = require(id);
        String nextName = command.name() != null ? command.name() : category.getName();
        String nextSlug = command.slug() != null ? command.slug() : category.getSlug();
        ensureUniqueIdentity(nextName, nextSlug, id);
        categoryMapper.applyPatch(command, category);
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException duplicate) {
            throw translateDuplicate(duplicate, nextName, nextSlug);
        }
    }

    public void deactivate(Long id) {
        require(id).deactivate();
    }

    public void activate(Long id) {
        require(id).activate();
    }

    public void delete(Long id) {
        Category category = require(id);
        if (productReference.countByCategoryId(id) > 0) {
            throw new CategoryInUseException(id);
        }
        categoryRepository.delete(category);
    }

    public void reassignAndDelete(Long sourceId, Long targetId) {
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("Reassignment target must be a different category");
        }
        Category source = require(sourceId);
        require(targetId);
        productReference.reassign(sourceId, targetId);
        categoryRepository.delete(source);
    }

    @Transactional(readOnly = true)
    public Category require(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
    }

    private void ensureUniqueIdentity(String name, String slug, Long excludeId) {
        String normalizedName = name.trim();
        String normalizedSlug = slug.trim().toLowerCase(java.util.Locale.ROOT);

        if (excludeId == null) {
            if (categoryRepository.existsByName(normalizedName)) {
                throw new DuplicateCategoryException(DuplicateCategoryException.Field.NAME, normalizedName);
            }
            if (categoryRepository.existsBySlug(normalizedSlug)) {
                throw new DuplicateCategoryException(DuplicateCategoryException.Field.SLUG, normalizedSlug);
            }
            return;
        }

        if (categoryRepository.existsByNameAndIdNot(normalizedName, excludeId)) {
            throw new DuplicateCategoryException(DuplicateCategoryException.Field.NAME, normalizedName);
        }
        if (categoryRepository.existsBySlugAndIdNot(normalizedSlug, excludeId)) {
            throw new DuplicateCategoryException(DuplicateCategoryException.Field.SLUG, normalizedSlug);
        }
    }

    private static DuplicateCategoryException translateDuplicate(
            DataIntegrityViolationException exception,
            String name,
            String slug) {
        String message = exception.getMessage();
        if (message != null && message.contains("uq_categories_slug")) {
            return new DuplicateCategoryException(DuplicateCategoryException.Field.SLUG, slug);
        }
        return new DuplicateCategoryException(DuplicateCategoryException.Field.NAME, name);
    }
}
