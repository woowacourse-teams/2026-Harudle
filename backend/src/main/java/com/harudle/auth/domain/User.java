package com.harudle.auth.domain;

import com.harudle.auth.domain.policy.NicknamePolicy;
import com.harudle.auth.domain.policy.ProfileImageCodePolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    private static final int DEFAULT_DAILY_GENERATION_LIMIT = 3;

    @Id
    private UUID id;

    @Column(name = "primary_email", length = 320)
    private String primaryEmail;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(name = "nickname", length = 30)
    private String nickname;

    @Column(name = "profile_image_code")
    private Short profileImageCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "daily_generation_limit", nullable = false)
    private int dailyGenerationLimit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {

    }

    public User(
            String primaryEmail,
            String name,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.primaryEmail = primaryEmail;
        this.name = name;
        this.role = UserRole.USER;
        this.dailyGenerationLimit = DEFAULT_DAILY_GENERATION_LIMIT;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void assignInitialPublicProfile(String rawNickname, int imageCode, Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        if (this.nickname != null) {
            throw new IllegalStateException("공개 프로필은 이미 배정되었습니다.");
        }

        String validatedNickname = NicknamePolicy.normalizeAndValidate(rawNickname);
        short validatedImageCode = ProfileImageCodePolicy.validateAndConvert(imageCode);

        this.nickname = validatedNickname;

        if (this.profileImageCode == null) {
            this.profileImageCode = validatedImageCode;
        }

        this.updatedAt = now;
    }

    public void changeNickname(String rawNickname, Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        if (this.nickname == null || this.profileImageCode == null) {
            throw new IllegalStateException("공개 프로필을 먼저 배정해야 합니다.");
        }

        String validatedNickname = NicknamePolicy.normalizeAndValidate(rawNickname);

        this.nickname = validatedNickname;
        this.updatedAt = now;
    }

    public void updateNameIfProvided(String name, Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");

        if (name == null || name.isBlank()) {
            return;
        }

        this.name = name;
        this.updatedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getPrimaryEmail() {
        return primaryEmail;
    }

    public String getName() {
        return name;
    }

    public String getNickname() {
        return nickname;
    }

    public Short getProfileImageCode() {
        return profileImageCode;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public int getDailyGenerationLimit() {
        return dailyGenerationLimit;
    }

    public void changeDailyGenerationLimit(int dailyGenerationLimit) {
        if (dailyGenerationLimit < 1) {
            throw new IllegalArgumentException("일일 생성 한도는 1 이상이어야 합니다.");
        }
        this.dailyGenerationLimit = dailyGenerationLimit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
