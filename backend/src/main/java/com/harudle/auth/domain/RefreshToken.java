package com.harudle.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    private static final int TOKEN_HASH_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = TOKEN_HASH_LENGTH, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected RefreshToken() {

    }

    public RefreshToken(
            User user,
            String tokenHash,
            Instant expiresAt,
            Instant createdAt
    ) {
        this.user = Objects.requireNonNull(user, "user는 필수입니다.");
        this.tokenHash = validateTokenHash(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt은 필수입니다.");
        validateExpiration();
    }

    public boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        return !now.isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean canUseAt(Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        if (isRevoked()) {
            return false;
        }

        return !isExpiredAt(now);
    }

    public void revoke(Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        if (isRevoked()) {
            return;
        }

        this.revokedAt = now;
    }

    private String validateTokenHash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tokenHash는 필수입니다.");
        }

        if (value.length() == TOKEN_HASH_LENGTH) {
            return value;
        }

        throw new IllegalArgumentException("tokenHash는 64자여야 합니다.");
    }

    private void validateExpiration() {
        if (expiresAt.isAfter(createdAt)) {
            return;
        }

        throw new IllegalArgumentException("expiresAt은 createdAt 이후여야 합니다.");
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
