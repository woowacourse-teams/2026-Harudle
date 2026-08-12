package com.harudle.generation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ComicGenerationTest {

    private static final Long GENERATION_PROMPT_ID = 1L;
    private static final String REQUEST_FINGERPRINT = "a".repeat(64);

    @Test
    @DisplayName("처리 중 상태의 만화 생성 작업을 시작한다")
    void startComicGeneration() {
        UUID diaryId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        ComicGeneration generation = ComicGeneration.start(
                diaryId,
                GENERATION_PROMPT_ID,
                idempotencyKey,
                REQUEST_FINGERPRINT
        );

        assertThat(generation.getId()).isNotNull();
        assertThat(generation.getDiaryId()).isEqualTo(diaryId);
        assertThat(generation.getGenerationPromptId()).isEqualTo(GENERATION_PROMPT_ID);
        assertThat(generation.matchesExecutableClaim(
                diaryId,
                idempotencyKey,
                REQUEST_FINGERPRINT
        )).isTrue();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(generation.getImageObjectKey()).isNull();
        assertThat(generation.getErrorCode()).isNull();
        assertThat(generation.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("유효하지 않은 요청 지문으로 생성 작업을 시작할 수 없다")
    void rejectInvalidRequestFingerprint() {
        assertThatThrownBy(() -> startGeneration("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("요청 지문");
    }

    @Test
    @DisplayName("처리 중인 생성 작업을 성공 상태로 완료한다")
    void succeedComicGeneration() {
        ComicGeneration generation = startGeneration();
        Storyboard storyboard = createStoryboard();
        Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");

        generation.succeed(storyboard, " generated/comic.png ", completedAt);

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(generation.getTitle()).isEqualTo(storyboard.title());
        assertThat(generation.getImageObjectKey()).isEqualTo("generated/comic.png");
        assertThat(generation.getErrorCode()).isNull();
        assertThat(generation.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("처리 중인 생성 작업을 실패 상태로 완료한다")
    void failComicGeneration() {
        ComicGeneration generation = startGeneration();
        Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");

        generation.fail(GenerationErrorCode.AI_PROVIDER_ERROR, completedAt);

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getImageObjectKey()).isNull();
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.AI_PROVIDER_ERROR);
        assertThat(generation.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("처리 제한 시간을 지난 생성 작업을 중단한다")
    void interruptStaleGeneration() {
        ComicGeneration generation = startGeneration();
        Instant currentTime = Instant.parse("2026-08-10T10:20:00Z");
        ReflectionTestUtils.setField(
                generation,
                "updatedAt",
                currentTime.minus(Duration.ofMinutes(16))
        );

        generation.interruptIfStale(currentTime, Duration.ofMinutes(15));

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        assertThat(generation.getCompletedAt()).isEqualTo(currentTime);
    }

    @Test
    @DisplayName("중단된 생성 작업을 실패 상태로 완료한다")
    void interruptComicGeneration() {
        ComicGeneration generation = startGeneration();
        Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");

        generation.interrupt(completedAt);

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        assertThat(generation.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("완료된 생성 작업을 다시 성공 처리할 수 없다")
    void rejectSucceedAfterFailure() {
        ComicGeneration generation = startGeneration();
        generation.fail(GenerationErrorCode.AI_PROVIDER_ERROR, Instant.now());

        assertThatThrownBy(() -> generation.succeed(createStoryboard(), "generated/comic.png", Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("처리 중");
    }

    @Test
    @DisplayName("완료된 생성 작업을 다시 실패 처리할 수 없다")
    void rejectFailAfterSuccess() {
        ComicGeneration generation = startGeneration();
        generation.succeed(createStoryboard(), "generated/comic.png", Instant.now());

        assertThatThrownBy(() -> generation.fail(GenerationErrorCode.AI_PROVIDER_ERROR, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("처리 중");
    }

    @Test
    @DisplayName("스토리보드가 없으면 생성 작업을 성공 처리할 수 없다")
    void rejectSucceedWithoutStoryboard() {
        ComicGeneration generation = startGeneration();

        assertThatThrownBy(() -> generation.succeed(null, "generated/comic.png", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("스토리보드");
    }

    @Test
    @DisplayName("이미지 Object Key가 UTF-8 기준 1,024바이트를 초과하면 성공 처리할 수 없다")
    void rejectLongImageObjectKey() {
        ComicGeneration generation = startGeneration();

        assertThatThrownBy(() -> generation.succeed(createStoryboard(), "가".repeat(342), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1,024바이트");
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(generation.getTitle()).isNull();
        assertThat(generation.getImageObjectKey()).isNull();
        assertThat(generation.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("오류 코드가 없으면 생성 작업을 실패 처리할 수 없다")
    void rejectFailureWithoutErrorCode() {
        ComicGeneration generation = startGeneration();

        assertThatThrownBy(() -> generation.fail(null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("오류 코드");
    }

    private ComicGeneration startGeneration() {
        return startGeneration(REQUEST_FINGERPRINT);
    }

    private ComicGeneration startGeneration(String requestFingerprint) {
        return ComicGeneration.start(
                UUID.randomUUID(),
                GENERATION_PROMPT_ID,
                UUID.randomUUID(),
                requestFingerprint
        );
    }

    private Storyboard createStoryboard() {
        return new Storyboard(
                "친구와 보낸 하루",
                "같은 주인공이 모든 패널에 등장한다.",
                List.of(
                        createPanel(1, "첫 번째 캡션"),
                        createPanel(2, "두 번째 캡션"),
                        createPanel(3, "세 번째 캡션"),
                        createPanel(4, "네 번째 캡션")
                )
        );
    }

    private StoryPanel createPanel(int panelNumber, String caption) {
        return new StoryPanel(
                panelNumber,
                caption,
                "장면 " + panelNumber,
                "등장인물 " + panelNumber,
                "감정 " + panelNumber,
                List.of("소품 " + panelNumber)
        );
    }
}
