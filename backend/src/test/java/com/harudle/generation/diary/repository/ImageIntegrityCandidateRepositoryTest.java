package com.harudle.generation.diary.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ImageIntegrityCandidateRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(300);
    private static final UUID FIRST_ID = new UUID(0, 1);
    private static final UUID DELETED_ID = new UUID(0, 2);
    private static final UUID FAILED_ID = new UUID(0, 3);
    private static final UUID LAST_ID = new UUID(0, 4);
    private static final UUID RECENT_ID = new UUID(0, 5);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ImageIntegrityCandidateRepository candidates;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private UUID userId;
    private long promptId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, name) VALUES (?, ?)", userId, "점검 테스트");
        promptId = jdbcTemplate.queryForObject("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text, image_style_prompt_text, image_asset_object_key
                ) VALUES (?, ?, ?) RETURNING id
                """, Long.class, "스토리보드", "이미지", "references/integrity.png");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        jdbcTemplate.update("DELETE FROM generation_prompts WHERE id = ?", promptId);
    }

    @Test
    void pagesOnlyActiveSucceededImagesOlderThanGracePeriod() {
        insertGeneration(FIRST_ID, "SUCCEEDED", NOW.minusSeconds(3600), false);
        insertGeneration(DELETED_ID, "SUCCEEDED", NOW.minusSeconds(3600), true);
        insertGeneration(FAILED_ID, "FAILED", NOW.minusSeconds(3600), false);
        insertGeneration(LAST_ID, "SUCCEEDED", NOW.minusSeconds(3600), false);
        insertGeneration(RECENT_ID, "SUCCEEDED", NOW.minusSeconds(60), false);

        List<ImageIntegrityCandidate> firstPage = candidates.findNext(null, CUTOFF, 1);
        List<ImageIntegrityCandidate> secondPage = candidates.findNext(FIRST_ID, CUTOFF, 1);

        assertThat(firstPage).extracting(ImageIntegrityCandidate::generationId).containsExactly(FIRST_ID);
        assertThat(secondPage).extracting(ImageIntegrityCandidate::generationId).containsExactly(LAST_ID);
        assertThat(candidates.findNext(LAST_ID, CUTOFF, 1)).isEmpty();
        assertThat(candidates.isStillVisible(firstPage.getFirst(), CUTOFF)).isTrue();

        jdbcTemplate.update("""
                UPDATE diaries SET deleted_at = ?
                WHERE id = (SELECT diary_id FROM diary_generations WHERE id = ?)
                """, Timestamp.from(NOW), FIRST_ID);
        assertThat(candidates.isStillVisible(firstPage.getFirst(), CUTOFF)).isFalse();
    }

    private void insertGeneration(UUID generationId, String status, Instant completedAt, boolean deleted) {
        UUID diaryId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO diaries (
                    id, user_id, diary_date, source_text, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, diaryId, userId, LocalDate.of(2026, 9, 29), "일기",
                Timestamp.from(completedAt), Timestamp.from(completedAt),
                deleted ? Timestamp.from(NOW) : null);
        jdbcTemplate.update("""
                INSERT INTO diary_generations (
                    id, diary_id, prompt_id, idempotency_key, request_fingerprint,
                    status, title, image_object_key, error_code, created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, generationId, diaryId, promptId, UUID.randomUUID(), "a".repeat(64),
                status, status.equals("SUCCEEDED") ? "완성" : null,
                status.equals("SUCCEEDED") ? "generated/integrity/" + generationId + ".png" : null,
                status.equals("FAILED") ? "AI_PROVIDER_ERROR" : null,
                Timestamp.from(completedAt), Timestamp.from(completedAt), Timestamp.from(completedAt));
    }
}
