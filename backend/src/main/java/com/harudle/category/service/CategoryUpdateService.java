package com.harudle.category.service;

import com.harudle.category.domain.Category;
import com.harudle.category.domain.exception.InvalidCategoryException;
import com.harudle.category.query.CategorySnapshot;
import com.harudle.category.repository.CategoryRepository;
import com.harudle.category.service.exception.CategoryNameAlreadyUsedException;
import com.harudle.category.service.exception.CategoryNotFoundException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryUpdateService {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final CategoryRepository repository;

    public CategoryUpdateService(CategoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CategorySnapshot update(long categoryId, String name, Integer sortOrder, Boolean active) {
        if (name == null && sortOrder == null && active == null) {
            throw new InvalidCategoryException("category", "수정할 항목을 하나 이상 보내 주세요.");
        }
        Category category = repository.findByIdForUpdate(categoryId)
                .orElseThrow(CategoryNotFoundException::new);
        category.update(name, sortOrder, active);
        save(category);
        return new CategorySnapshot(category.getId(), category.getName(), category.getSortOrder(), category.isActive());
    }

    private void save(Category category) {
        try {
            repository.saveAndFlush(category);
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && UNIQUE_VIOLATION_SQL_STATE.equals(violation.getSQLState())
                        && "uq_categories_name".equals(violation.getConstraintName())) {
                    throw new CategoryNameAlreadyUsedException(exception);
                }
            }
            throw exception;
        }
    }
}
