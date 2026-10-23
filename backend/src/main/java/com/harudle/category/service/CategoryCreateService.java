package com.harudle.category.service;

import com.harudle.category.domain.Category;
import com.harudle.category.domain.exception.InvalidCategoryException;
import com.harudle.category.query.CategorySnapshot;
import com.harudle.category.repository.CategoryRepository;
import com.harudle.category.service.exception.CategoryNameAlreadyUsedException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryCreateService {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final CategoryRepository repository;

    public CategoryCreateService(CategoryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public CategorySnapshot create(String name, Integer sortOrder) {
        int order = resolveSortOrder(sortOrder);
        Category category = save(new Category(name, order));
        return new CategorySnapshot(category.getId(), category.getName(), category.getSortOrder(), category.isActive());
    }

    private int resolveSortOrder(Integer sortOrder) {
        if (sortOrder != null) {
            return sortOrder;
        }
        int lastOrder = repository.findMaxSortOrder();
        if (lastOrder == Integer.MAX_VALUE) {
            throw new InvalidCategoryException("sortOrder", "마지막 순서가 최대값입니다. 표시 순서를 직접 지정해 주세요.");
        }
        return lastOrder + 1;
    }

    private Category save(Category category) {
        try {
            return repository.saveAndFlush(category);
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
