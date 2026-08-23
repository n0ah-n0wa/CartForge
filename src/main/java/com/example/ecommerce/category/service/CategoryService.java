package com.example.ecommerce.category.service;

import com.example.ecommerce.category.dto.CreateCategoryCommand;
import com.example.ecommerce.category.dto.UpdateCategoryCommand;
import com.example.ecommerce.category.entity.Category;
import com.example.ecommerce.category.mapper.CategoryMapper;
import com.example.ecommerce.category.repository.CategoryRepository;
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

    public Category create(CreateCategoryCommand command) {
        return categoryRepository.save(categoryMapper.toEntity(command));
    }

    public Category update(Long id, UpdateCategoryCommand command) {
        Category category = require(id);
        categoryMapper.apply(command, category);
        return category;
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
}
