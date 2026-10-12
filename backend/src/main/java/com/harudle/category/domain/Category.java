package com.harudle.category.domain;

import com.harudle.category.domain.exception.InvalidCategoryException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.text.Normalizer;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() {
    }

    public Category(String rawName, int sortOrder) {
        if (rawName == null || rawName.isBlank()) {
            throw new InvalidCategoryException("name", "카테고리 이름은 필수입니다.");
        }
        String name = Normalizer.normalize(rawName.strip(), Normalizer.Form.NFC);
        if (name.codePointCount(0, name.length()) > 20) {
            throw new InvalidCategoryException("name", "카테고리 이름은 1자 이상 20자 이하여야 합니다.");
        }
        if (sortOrder < 0) {
            throw new InvalidCategoryException("sortOrder", "표시 순서는 0 이상이어야 합니다.");
        }
        this.name = name;
        this.sortOrder = sortOrder;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }
}
