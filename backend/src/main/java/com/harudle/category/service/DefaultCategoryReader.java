package com.harudle.category.service;

import com.harudle.category.query.CategorySnapshot;
import com.harudle.category.repository.CategoryRepository;
import com.harudle.category.service.exception.CategoryInactiveException;
import com.harudle.category.service.exception.CategoryNotFoundException;
import com.harudle.category.service.port.CategoryReader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DefaultCategoryReader implements CategoryReader {

    private final CategoryRepository repository;

    public DefaultCategoryReader(CategoryRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Category get(long categoryId) {
        CategorySnapshot category = repository.findById(categoryId)
                .orElseThrow(CategoryNotFoundException::new);
        return toCategory(category);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Category lockActiveForPublication(long categoryId) {
        CategorySnapshot category = repository.findByIdForShare(categoryId)
                .orElseThrow(CategoryNotFoundException::new);
        if (!category.active()) {
            throw new CategoryInactiveException();
        }
        return toCategory(category);
    }

    private Category toCategory(CategorySnapshot category) {
        return new Category(category.id(), category.name(), category.sortOrder(), category.active());
    }
}
