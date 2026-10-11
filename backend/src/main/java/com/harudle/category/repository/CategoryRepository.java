package com.harudle.category.repository;

import com.harudle.category.domain.Category;
import com.harudle.category.query.CategorySnapshot;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface CategoryRepository extends Repository<Category, Long> {

    @Query("""
            SELECT new com.harudle.category.query.CategorySnapshot(
                category.id, category.name, category.sortOrder, category.active
            )
            FROM Category category
            WHERE category.id = :categoryId
            """)
    Optional<CategorySnapshot> findById(@Param("categoryId") long categoryId);

    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            SELECT id, name, sort_order, is_active
            FROM categories
            WHERE id = :categoryId
            FOR SHARE
            """, nativeQuery = true)
    Optional<CategorySnapshot> findByIdForShare(@Param("categoryId") long categoryId);
}
