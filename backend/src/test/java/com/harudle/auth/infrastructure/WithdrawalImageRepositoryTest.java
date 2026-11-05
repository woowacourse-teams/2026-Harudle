package com.harudle.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.support.ServiceIntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class WithdrawalImageRepositoryTest extends ServiceIntegrationTestSupport {

    @Autowired private WithdrawalImageRepository repository;
    @Autowired private JdbcTemplate jdbc;
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private Long promptId;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM profile_image_cleanup_tasks WHERE user_id IN (?, ?)", userId, otherUserId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userId, otherUserId);
        if (promptId != null) {
            jdbc.update("DELETE FROM generation_prompts WHERE id = ?", promptId);
        }
    }

    @Test
    @DisplayName("삭제 표시된 일기와 정리 예정인 프로필을 포함해 본인의 이미지 경로만 조회한다")
    void findOnlyMembersImagesIncludingDeletedDiariesAndPendingProfiles() {
        jdbc.update("INSERT INTO users (id, name, profile_image_object_key) VALUES (?, ?, ?)",
                userId, "회원", "profile/current.webp");
        jdbc.update("INSERT INTO users (id, name, profile_image_object_key) VALUES (?, ?, ?)",
                otherUserId, "다른 회원", "profile/other.webp");
        jdbc.update("INSERT INTO profile_image_cleanup_tasks (user_id, object_key, created_at, delete_after) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '1 day')", userId, "profile/old.webp");
        jdbc.update("INSERT INTO profile_image_cleanup_tasks (user_id, object_key, created_at, delete_after) "
                + "VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", otherUserId, "profile/other-old.webp");
        promptId = jdbc.queryForObject("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '스타일', 'references/withdrawal.png') RETURNING id
                """, Long.class);
        insertGeneration(userId, "generated/own.webp", true);
        insertGeneration(otherUserId, "generated/other.webp", false);

        var targets = repository.findTargets(userId);

        assertThat(targets.diaryImages()).containsExactly("generated/own.webp");
        assertThat(targets.profileImages()).containsExactlyInAnyOrder("profile/current.webp", "profile/old.webp");
        assertThat(targets.toString()).doesNotContain("generated/own.webp", "profile/current.webp", "profile/old.webp");
        assertThat(repository.findTargets(UUID.randomUUID()).diaryImages()).isEmpty();
    }

    private void insertGeneration(UUID owner, String imageKey, boolean deleted) {
        UUID diaryId = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text, deleted_at) "
                + "VALUES (?, ?, DATE '2026-10-20', '일기', CASE WHEN ? THEN now() ELSE NULL END)",
                diaryId, owner, deleted);
        jdbc.update("""
                INSERT INTO diary_generations (id, diary_id, requested_by_user_id, prompt_id, idempotency_key,
                                               request_fingerprint, status, image_object_key)
                VALUES (?, ?, ?, ?, ?, ?, 'SUCCEEDED', ?)
                """, UUID.randomUUID(), diaryId, owner, promptId, UUID.randomUUID(), "a".repeat(64), imageKey);
    }
}
