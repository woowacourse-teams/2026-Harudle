package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.service.dto.GenerateComicCommand;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestFingerprintGeneratorTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final String DIARY_TEXT = "오늘 친구와 카페에 갔다.";
    private static final String EXPECTED_FINGERPRINT =
            "e38114eac361a571f84ce04f677a736d27ef57fa1f9647240e75fd5286ffb3cf";

    private final RequestFingerprintGenerator generator = new RequestFingerprintGenerator();

    @Test
    @DisplayName("API 명세의 정규화 규칙으로 요청 지문을 생성한다")
    void generateRequestFingerprint() {
        String fingerprint = generator.generate(createCommand(USER_ID, DIARY_DATE, DIARY_TEXT));

        assertThat(fingerprint).isEqualTo(EXPECTED_FINGERPRINT);
        assertThat(fingerprint).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("동일한 사용자와 날짜와 일기 내용은 동일한 요청 지문을 생성한다")
    void generateSameFingerprintForSameRequest() {
        GenerateComicCommand firstCommand = createCommand(USER_ID, DIARY_DATE, DIARY_TEXT);
        GenerateComicCommand secondCommand = createCommand(USER_ID, DIARY_DATE, DIARY_TEXT);

        assertThat(generator.generate(firstCommand)).isEqualTo(generator.generate(secondCommand));
    }

    @Test
    @DisplayName("사용자와 날짜와 일기 내용 중 하나가 다르면 다른 요청 지문을 생성한다")
    void generateDifferentFingerprintForDifferentRequest() {
        String original = generator.generate(createCommand(USER_ID, DIARY_DATE, DIARY_TEXT));
        String differentUser = generator.generate(createCommand(UUID.randomUUID(), DIARY_DATE, DIARY_TEXT));
        String differentDate = generator.generate(createCommand(USER_ID, DIARY_DATE.plusDays(1), DIARY_TEXT));
        String differentText = generator.generate(createCommand(USER_ID, DIARY_DATE, "다른 일기 내용"));

        assertThat(List.of(original, differentUser, differentDate, differentText)).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("일기 ID와 멱등성 키는 요청 지문에 포함하지 않는다")
    void ignoreDiaryIdAndIdempotencyKey() {
        GenerateComicCommand firstCommand = createCommand(USER_ID, DIARY_DATE, DIARY_TEXT);
        GenerateComicCommand secondCommand = createCommand(USER_ID, DIARY_DATE, DIARY_TEXT);

        assertThat(firstCommand.diaryId()).isNotEqualTo(secondCommand.diaryId());
        assertThat(firstCommand.idempotencyKey()).isNotEqualTo(secondCommand.idempotencyKey());
        assertThat(generator.generate(firstCommand)).isEqualTo(generator.generate(secondCommand));
    }

    @Test
    @DisplayName("만화 생성 명령이 없으면 요청 지문을 생성할 수 없다")
    void rejectNullCommand() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만화 생성 명령");
    }

    private GenerateComicCommand createCommand(UUID userId, LocalDate diaryDate, String diaryText) {
        return new GenerateComicCommand(
                userId,
                UUID.randomUUID(),
                diaryDate,
                diaryText,
                UUID.randomUUID()
        );
    }
}
