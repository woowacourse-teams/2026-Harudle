package com.harudle.guest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "guest_sessions")
public class GuestSession {
    private static final int TOKEN_HASH_LENGTH = 64;

    @Id
    private UUID id;

    @Column(name = "guest_user_id", nullable = false, unique = true)
    private UUID guestUserId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = TOKEN_HASH_LENGTH, unique = true)
    private String tokenHash;

    @Column(name = "diary_id", unique = true)
    private UUID diaryId;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GuestSession() {
    }

    private GuestSession(
            UUID id,
            UUID guestUserId,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "세션 ID는 필수입니다.");
        this.guestUserId = Objects.requireNonNull(guestUserId, "게스트 사용자 ID는 필수입니다.");
        this.tokenHash = validateTokenHash(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt, "만료 시각은 필수입니다.");
        this.createdAt = Objects.requireNonNull(createdAt, "생성 시각은 필수입니다.");
        this.updatedAt = createdAt;

        validateExpiration();
    }

    public static GuestSession create(
            UUID guestUserId,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt
    ) {
        return new GuestSession(
                UUID.randomUUID(),
                guestUserId,
                tokenHash,
                expiresAt,
                createdAt
        );
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "현재 시각은 필수입니다.");
        return !now.isBefore(expiresAt);
    }

    private String validateTokenHash(String tokenHash) {
        if (tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("토큰 해시는 64자리 소문자 16진수여야 합니다.");
        }

        return tokenHash;
    }

    private void validateExpiration() {
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("만료 시각은 생성 시각 이후여야 합니다.");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getGuestUserId() {
        return guestUserId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getDiaryId() {
        return diaryId;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
