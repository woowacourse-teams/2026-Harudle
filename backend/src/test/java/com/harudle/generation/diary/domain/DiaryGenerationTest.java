package com.harudle.generation.diary.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class DiaryGenerationTest {

    private static final Long GENERATION_PROMPT_ID = 1L;
    private static final String REQUEST_FINGERPRINT = "a".repeat(64);

    @Test
    @DisplayName("처리 중 상태의 그림일기 생성 작업을 시작한다")
    void startDiaryGeneration() {
        UUID diaryId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();

        DiaryGeneration generation = DiaryGeneration.start(
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
        assertThat(generation.getStoryboardProvider()).isNull();
        assertThat(generation.getStoryboardModel()).isNull();
        assertThat(generation.getUsageDate()).isNull();
    }

    @Test
    @DisplayName("생성 요청의 사용량 날짜를 함께 보존한다")
    void startDiaryGenerationWithUsageDate() {
        LocalDate usageDate = LocalDate.of(2026, 8, 6);

        DiaryGeneration generation = DiaryGeneration.start(
                UUID.randomUUID(),
                GENERATION_PROMPT_ID,
                UUID.randomUUID(),
                REQUEST_FINGERPRINT,
                usageDate
        );

        assertThat(generation.getUsageDate()).isEqualTo(usageDate);
    }

    @Test
    @DisplayName("대기 작업은 요청 정보와 사용량 날짜를 보존하고 아직 실행할 수 없다")
    void createPendingGeneration() {
        UUID diaryId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        LocalDate usageDate = LocalDate.of(2026, 10, 20);

        DiaryGeneration generation = DiaryGeneration.pending(
                diaryId, GENERATION_PROMPT_ID, idempotencyKey, REQUEST_FINGERPRINT, usageDate
        );

        assertThat(generation.getId()).isNotNull();
        assertThat(generation.getDiaryId()).isEqualTo(diaryId);
        assertThat(generation.getGenerationPromptId()).isEqualTo(GENERATION_PROMPT_ID);
        assertThat(generation.hasSameRequestFingerprint(REQUEST_FINGERPRINT)).isTrue();
        assertThat(generation.getUsageDate()).isEqualTo(usageDate);
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(generation.matchesExecutableClaim(diaryId, idempotencyKey, REQUEST_FINGERPRINT)).isFalse();
        assertThat(generation.getStoryboard()).isNull();
        assertThat(generation.getTitle()).isNull();
        assertThat(generation.getImageObjectKey()).isNull();
        assertThat(generation.getErrorCode()).isNull();
        assertThat(generation.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("대기 작업을 처리 중으로 바꾸면 같은 요청으로 실행할 수 있다")
    void startProcessingPendingGeneration() {
        UUID diaryId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        LocalDate usageDate = LocalDate.of(2026, 10, 20);
        DiaryGeneration generation = DiaryGeneration.pending(
                diaryId, GENERATION_PROMPT_ID, idempotencyKey, REQUEST_FINGERPRINT, usageDate
        );

        generation.startProcessing();

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
        assertThat(generation.matchesExecutableClaim(diaryId, idempotencyKey, REQUEST_FINGERPRINT)).isTrue();
        assertThat(generation.getUsageDate()).isEqualTo(usageDate);
        assertThat(generation.getErrorCode()).isNull();
        assertThat(generation.getCompletedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PROCESSING", "SUCCEEDED", "FAILED"})
    @DisplayName("이미 시작했거나 끝난 작업을 다시 처리 중으로 바꿀 수 없다")
    void rejectStartingNonPendingGeneration(GenerationStatus status) {
        DiaryGeneration generation = startGeneration();
        if (status == GenerationStatus.SUCCEEDED) {
            generation.succeed(createStoryboard(), "generated/diary-image.png", Instant.now());
        }
        if (status == GenerationStatus.FAILED) {
            generation.fail(GenerationErrorCode.AI_PROVIDER_ERROR, Instant.now());
        }

        assertThatThrownBy(generation::startProcessing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("대기 중인 생성 작업만 처리할 수 있습니다.");
        assertThat(generation.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("일반 완료 메서드로 대기 작업을 성공이나 실패로 바꿀 수 없다")
    void rejectCompletingPendingGeneration() {
        DiaryGeneration generation = DiaryGeneration.pending(
                UUID.randomUUID(), GENERATION_PROMPT_ID, UUID.randomUUID(), REQUEST_FINGERPRINT, null
        );
        Instant completedAt = Instant.parse("2026-10-20T10:00:00Z");

        assertThatThrownBy(() -> generation.succeed(createStoryboard(), "generated/diary-image.png", completedAt))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> generation.fail(GenerationErrorCode.AI_PROVIDER_ERROR, completedAt))
                .isInstanceOf(IllegalStateException.class);
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(generation.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("오래 대기한 작업에 처리 중인 작업의 제한 시간을 적용하지 않는다")
    void doNotInterruptPendingGeneration() {
        DiaryGeneration generation = DiaryGeneration.pending(
                UUID.randomUUID(), GENERATION_PROMPT_ID, UUID.randomUUID(), REQUEST_FINGERPRINT, null
        );
        Instant currentTime = Instant.parse("2026-10-20T10:00:00Z");
        ReflectionTestUtils.setField(generation, "updatedAt", currentTime.minus(Duration.ofHours(1)));

        assertThat(generation.interruptIfStale(currentTime, Duration.ofMinutes(15))).isFalse();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(generation.getErrorCode()).isNull();
        assertThat(generation.getCompletedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {120, 180})
    @DisplayName("대기 제한 시간에 도달한 작업을 한 번만 만료하고 완료 시각을 유지한다")
    void expirePendingGenerationOnlyOnce(long ageSeconds) {
        Instant currentTime = Instant.parse("2026-10-20T10:00:00Z");
        DiaryGeneration generation = pendingGeneration(currentTime.minusSeconds(ageSeconds));
        ReflectionTestUtils.setField(generation, "updatedAt", currentTime);

        assertThat(generation.expirePendingIfStale(currentTime, Duration.ofSeconds(120))).isTrue();
        assertThat(generation.expirePendingIfStale(currentTime.plusSeconds(1), Duration.ofSeconds(120))).isFalse();

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_PENDING_TIMEOUT);
        assertThat(generation.getCompletedAt()).isEqualTo(currentTime);
        assertThat(generation.getImageObjectKey()).isNull();
    }

    @Test
    @DisplayName("대기 제한 시간 직전의 작업은 만료하지 않는다")
    void doNotExpirePendingGenerationBeforeTimeout() {
        Instant currentTime = Instant.parse("2026-10-20T10:00:00Z");
        DiaryGeneration generation = pendingGeneration(currentTime.minusSeconds(120).plusNanos(1));

        assertThat(generation.expirePendingIfStale(currentTime, Duration.ofSeconds(120))).isFalse();

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(generation.getErrorCode()).isNull();
        assertThat(generation.getCompletedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PROCESSING", "SUCCEEDED", "FAILED"})
    @DisplayName("이미 실행 중이거나 끝난 작업은 대기 시간으로 만료하지 않는다")
    void doNotExpireNonPendingGeneration(GenerationStatus status) {
        Instant currentTime = Instant.parse("2026-10-20T10:00:00Z");
        DiaryGeneration generation = startGeneration();
        ReflectionTestUtils.setField(generation, "createdAt", currentTime.minusSeconds(180));
        if (status == GenerationStatus.SUCCEEDED) {
            generation.succeed(createStoryboard(), "generated/diary-image.png", currentTime.minusSeconds(1));
        }
        if (status == GenerationStatus.FAILED) {
            generation.fail(GenerationErrorCode.AI_PROVIDER_ERROR, currentTime.minusSeconds(1));
        }
        GenerationErrorCode originalError = generation.getErrorCode();
        Instant originalCompletedAt = generation.getCompletedAt();

        assertThat(generation.expirePendingIfStale(currentTime, Duration.ofSeconds(120))).isFalse();

        assertThat(generation.getStatus()).isEqualTo(status);
        assertThat(generation.getErrorCode()).isEqualTo(originalError);
        assertThat(generation.getCompletedAt()).isEqualTo(originalCompletedAt);
    }

    @Test
    @DisplayName("저장 시각이 없는 대기 작업은 만료하지 않는다")
    void doNotExpirePendingGenerationWithoutCreatedAt() {
        DiaryGeneration generation = pendingGeneration(null);

        assertThat(generation.expirePendingIfStale(Instant.now(), Duration.ofSeconds(120))).isFalse();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    @DisplayName("대기 제한 시간이 없거나 양수가 아니면 만료를 거절한다")
    void rejectInvalidPendingTimeout(Long seconds) {
        Instant currentTime = Instant.parse("2026-10-20T10:00:00Z");
        DiaryGeneration generation = pendingGeneration(currentTime.minusSeconds(180));
        Duration timeout = seconds == null ? null : Duration.ofSeconds(seconds);

        assertThatThrownBy(() -> generation.expirePendingIfStale(currentTime, timeout))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("생성 대기 제한 시간은 양수여야 합니다.");
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PENDING);
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
    void succeedDiaryGeneration() {
        DiaryGeneration generation = startGeneration();
        Storyboard storyboard = createStoryboard();
        Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");

        generation.succeed(storyboard, " generated/diary-image.png ", completedAt);

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.SUCCEEDED);
        assertThat(generation.getTitle()).isEqualTo(storyboard.title());
        assertThat(generation.getImageObjectKey()).isEqualTo("generated/diary-image.png");
        assertThat(generation.getErrorCode()).isNull();
        assertThat(generation.getCompletedAt()).isEqualTo(completedAt);
        assertThat(generation.getStoryboardProvider()).isNull();
        assertThat(generation.getStoryboardModel()).isNull();
    }

    @Test
    @DisplayName("처리 중인 생성 작업을 실패 상태로 완료한다")
    void failDiaryGeneration() {
        DiaryGeneration generation = startGeneration();
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
        DiaryGeneration generation = startGeneration();
        Instant currentTime = Instant.parse("2026-08-10T10:20:00Z");
        ReflectionTestUtils.setField(
                generation,
                "updatedAt",
                currentTime.minus(Duration.ofMinutes(16))
        );

        boolean interrupted = generation.interruptIfStale(currentTime, Duration.ofMinutes(15));

        assertThat(interrupted).isTrue();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        assertThat(generation.getCompletedAt()).isEqualTo(currentTime);
    }

    @Test
    @DisplayName("만료 경계에서는 한 번만 중단 성공을 반환한다")
    void interruptAtTimeoutOnlyOnce() {
        DiaryGeneration generation = startGeneration();
        Instant currentTime = Instant.parse("2026-08-10T10:00:00Z");
        Duration timeout = Duration.ofMinutes(15);
        ReflectionTestUtils.setField(generation, "updatedAt", currentTime.minus(timeout));

        assertThat(generation.interruptIfStale(currentTime, timeout)).isTrue();
        assertThat(generation.interruptIfStale(currentTime, timeout)).isFalse();
        assertThat(generation.getCompletedAt()).isEqualTo(currentTime);
    }

    @Test
    @DisplayName("만료되지 않은 작업은 중단하지 않는다")
    void doNotInterruptRecentGeneration() {
        DiaryGeneration generation = startGeneration();
        Instant currentTime = Instant.parse("2026-08-10T10:00:00Z");
        ReflectionTestUtils.setField(generation, "updatedAt", currentTime.minus(Duration.ofMinutes(14)));

        assertThat(generation.interruptIfStale(currentTime, Duration.ofMinutes(15))).isFalse();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
    }

    @Test
    @DisplayName("갱신 시각이 없는 작업은 중단하지 않는다")
    void doNotInterruptGenerationWithoutUpdatedAt() {
        DiaryGeneration generation = startGeneration();

        assertThat(generation.interruptIfStale(Instant.now(), Duration.ofMinutes(15))).isFalse();
        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.PROCESSING);
    }

    @Test
    @DisplayName("중단된 생성 작업을 실패 상태로 완료한다")
    void interruptDiaryGeneration() {
        DiaryGeneration generation = startGeneration();
        Instant completedAt = Instant.parse("2026-08-10T10:00:00Z");

        generation.interrupt(completedAt);

        assertThat(generation.getStatus()).isEqualTo(GenerationStatus.FAILED);
        assertThat(generation.getErrorCode()).isEqualTo(GenerationErrorCode.GENERATION_INTERRUPTED);
        assertThat(generation.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("완료된 생성 작업을 다시 성공 처리할 수 없다")
    void rejectSucceedAfterFailure() {
        DiaryGeneration generation = startGeneration();
        generation.fail(GenerationErrorCode.AI_PROVIDER_ERROR, Instant.now());

        assertThatThrownBy(() -> generation.succeed(createStoryboard(), "generated/diary-image.png", Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("처리 중");
    }

    @Test
    @DisplayName("완료된 생성 작업을 다시 실패 처리할 수 없다")
    void rejectFailAfterSuccess() {
        DiaryGeneration generation = startGeneration();
        generation.succeed(createStoryboard(), "generated/diary-image.png", Instant.now());

        assertThatThrownBy(() -> generation.fail(GenerationErrorCode.AI_PROVIDER_ERROR, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("처리 중");
    }

    @Test
    @DisplayName("스토리보드가 없으면 생성 작업을 성공 처리할 수 없다")
    void rejectSucceedWithoutStoryboard() {
        DiaryGeneration generation = startGeneration();

        assertThatThrownBy(() -> generation.succeed(null, "generated/diary-image.png", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("스토리보드");
    }

    @Test
    @DisplayName("이미지 Object Key가 UTF-8 기준 1,024바이트를 초과하면 성공 처리할 수 없다")
    void rejectLongImageObjectKey() {
        DiaryGeneration generation = startGeneration();

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
        DiaryGeneration generation = startGeneration();

        assertThatThrownBy(() -> generation.fail(null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("오류 코드");
    }

    private DiaryGeneration pendingGeneration(Instant createdAt) {
        DiaryGeneration generation = DiaryGeneration.pending(
                UUID.randomUUID(), GENERATION_PROMPT_ID, UUID.randomUUID(), REQUEST_FINGERPRINT, null
        );
        ReflectionTestUtils.setField(generation, "createdAt", createdAt);
        return generation;
    }

    private DiaryGeneration startGeneration() {
        return startGeneration(REQUEST_FINGERPRINT);
    }

    private DiaryGeneration startGeneration(String requestFingerprint) {
        return DiaryGeneration.start(
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
