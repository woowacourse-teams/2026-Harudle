package com.harudle.feed.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "feeds")
public class Feed {

    @Id
    private UUID id;

    @Column(name = "diary_id", nullable = false, updatable = false)
    private UUID diaryId;

    @Column(name = "category_id", nullable = false, updatable = false)
    private long categoryId;

    @Column(name = "published_at", nullable = false, updatable = false)
    private Instant publishedAt;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "comment_count", nullable = false)
    private int commentCount;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Feed() {
    }

    private Feed(UUID diaryId, long categoryId, Instant publishedAt) {
        this.diaryId = Objects.requireNonNull(diaryId, "게시할 일기 ID는 필수입니다.");
        if (categoryId <= 0) {
            throw new IllegalArgumentException("카테고리 ID는 양수여야 합니다.");
        }
        this.categoryId = categoryId;
        this.publishedAt = Objects.requireNonNull(publishedAt, "게시 시각은 필수입니다.")
                .truncatedTo(ChronoUnit.MICROS);
        this.id = UUID.randomUUID();
    }

    public static Feed publish(UUID diaryId, long categoryId, Instant publishedAt) {
        return new Feed(diaryId, categoryId, publishedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getDiaryId() {
        return diaryId;
    }

    public long getCategoryId() {
        return categoryId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public int getCommentCount() {
        return commentCount;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
