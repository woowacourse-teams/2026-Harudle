package com.harudle.generation.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    @DisplayName("정상 물음표 본문은 기존 정규화 요청의 SHA 지문을 그대로 유지한다")
    void preserveCanonicalFingerprintForValidQuestionMark() {
        String fingerprint = generator.generate(createCommand(USER_ID, DIARY_DATE, "?"));

        assertThat(fingerprint).isEqualTo("1121ef32f1f163b209002e032fea1a554250ee16a3518d16dbfb0b69471edc52");
    }

    @ParameterizedTest
    @ValueSource(ints = {0xD800, 0xDC00})
    @DisplayName("고립된 상위 및 하위 서로게이트는 물음표로 치환하지 않고 지문 생성을 거절한다")
    void rejectUnpairedSurrogatesBeforeHashing(int surrogate) {
        String malformedText = String.valueOf((char) surrogate);

        assertThatThrownBy(() -> generator.generate(createCommand(USER_ID, DIARY_DATE, malformedText)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("요청 지문에 유효하지 않은 문자가 포함되어 있습니다.")
                .hasNoCause();
    }

    @Test
    @DisplayName("동일한 사용자와 날짜와 일기 내용은 동일한 요청 지문을 생성한다")
    void generateSameFingerprintForSameRequest() {
        GenerateDiaryImageCommand firstCommand = createCommand(USER_ID, DIARY_DATE, DIARY_TEXT);
        GenerateDiaryImageCommand secondCommand = createCommand(USER_ID, DIARY_DATE, DIARY_TEXT);

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
        GenerateDiaryImageCommand firstCommand = createCommand(USER_ID, DIARY_DATE, DIARY_TEXT);
        GenerateDiaryImageCommand secondCommand = createCommand(USER_ID, DIARY_DATE, DIARY_TEXT);

        assertThat(firstCommand.diaryId()).isNotEqualTo(secondCommand.diaryId());
        assertThat(firstCommand.idempotencyKey()).isNotEqualTo(secondCommand.idempotencyKey());
        assertThat(generator.generate(firstCommand)).isEqualTo(generator.generate(secondCommand));
    }

    @Test
    @DisplayName("그림일기 생성 명령이 없으면 요청 지문을 생성할 수 없다")
    void rejectNullCommand() {
        assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("그림일기 이미지 생성 명령");
    }

    private GenerateDiaryImageCommand createCommand(UUID userId, LocalDate diaryDate, String diaryText) {
        return new GenerateDiaryImageCommand(
                userId,
                UUID.randomUUID(),
                diaryDate,
                diaryText,
                UUID.randomUUID()
        );
    }
}
