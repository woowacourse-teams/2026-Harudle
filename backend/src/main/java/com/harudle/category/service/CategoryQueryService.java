package com.harudle.category.service;

import com.harudle.category.query.CategorySnapshot;
import com.harudle.category.repository.CategoryRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryRepository repository;

    public CategoryQueryService(CategoryRepository repository) {
        this.repository = repository;
    }

    public List<CategorySnapshot> findPublicCategories() {
        return repository.findAllActive();
    }

    public List<CategorySnapshot> findAdminCategories() {
        return repository.findAll();
    }
}
