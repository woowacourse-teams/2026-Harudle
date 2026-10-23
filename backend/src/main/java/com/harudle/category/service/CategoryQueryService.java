package com.harudle.category.service;

import com.harudle.category.query.CategorySnapshot;
import com.harudle.category.repository.CategoryRepository;
import com.harudle.common.testdata.DevTestDataVisibility;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryRepository repository;
    private final DevTestDataVisibility visibility;

    public CategoryQueryService(CategoryRepository repository, DevTestDataVisibility visibility) {
        this.repository = repository;
        this.visibility = visibility;
    }

    public List<CategorySnapshot> findPublicCategories(@Nullable UUID viewerId) {
        return visibility.enabled() ? repository.findVisibleActive(viewerId) : repository.findAllActive();
    }

    public List<CategorySnapshot> findAdminCategories() {
        return repository.findAll();
    }
}
