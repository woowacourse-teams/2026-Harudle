package com.harudle.category.repository;

import com.harudle.category.domain.Category;
import com.harudle.category.query.CategorySnapshot;
import com.harudle.common.testdata.DevTestDataVisibility;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface CategoryRepository extends Repository<Category, Long> {

    Category saveAndFlush(Category category);

    @Query("SELECT COALESCE(MAX(category.sortOrder), -1) FROM Category category")
    int findMaxSortOrder();

    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT category FROM Category category WHERE category.id = :categoryId")
    Optional<Category> findByIdForUpdate(@Param("categoryId") long categoryId);

    @Query("""
            SELECT new com.harudle.category.query.CategorySnapshot(
                category.id, category.name, category.sortOrder, category.active
            )
            FROM Category category
            WHERE category.active = TRUE
            ORDER BY category.sortOrder, category.id
            """)
    List<CategorySnapshot> findAllActive();

    @Query(value = """
            SELECT id, name, sort_order, is_active FROM categories
            WHERE is_active = TRUE AND (name !~ '^부하[0-9a-f]{12}$' OR
            """ + DevTestDataVisibility.TEST_VIEWER_SQL + ") ORDER BY sort_order, id", nativeQuery = true)
    List<CategorySnapshot> findVisibleActive(@Param("viewerId") @Nullable UUID viewerId);

    @Query("""
            SELECT new com.harudle.category.query.CategorySnapshot(
                category.id, category.name, category.sortOrder, category.active
            )
            FROM Category category
            ORDER BY category.sortOrder, category.id
            """)
    List<CategorySnapshot> findAll();

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
