package com.harudle.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ReportSubmissionTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "　"})
    @DisplayName("선택 상세 내용이 없으면 빈 문자열로 정규화한다")
    void acceptsMissingDescription(String value) {
        assertThat(submission(value).description()).isEmpty();
    }

    @Test
    @DisplayName("일반 텍스트의 내부 공백과 이모지를 보존하고 코드 포인트로 길이를 제한한다")
    void preservesPlainTextAndAllowsFiveHundredCodePoints() {
        assertThat(submission(" \n<script>상세</script>\n둘째 줄 \t").description())
                .isEqualTo("<script>상세</script>\n둘째 줄");
        assertThat(submission("😀".repeat(500)).description()).isEqualTo("😀".repeat(500));
        assertThatThrownBy(() -> submission("😀".repeat(501))).isInstanceOf(InvalidReportException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\0", "앞\0뒤", "\uD800", "\uDC00", "앞\uD800뒤"})
    @DisplayName("DB가 저장할 수 없는 문자와 잘못된 유니코드를 치환 없이 거절한다")
    void rejectsUnsupportedCharacters(String value) {
        assertThatThrownBy(() -> submission(value)).isInstanceOf(InvalidReportException.class)
                .hasNoCause().hasMessageNotContaining(value);
    }

    @Test
    @DisplayName("대상 유형과 ID 및 신고 사유는 필수다")
    void rejectsMissingTargetOrReason() {
        assertThatThrownBy(() -> new ReportSubmission(null, UUID.randomUUID(), ReportReason.SPAM, ""))
                .isInstanceOf(InvalidReportException.class);
        assertThatThrownBy(() -> new ReportSubmission(ReportTargetType.USER, null, ReportReason.SPAM, ""))
                .isInstanceOf(InvalidReportException.class);
        assertThatThrownBy(() -> new ReportSubmission(ReportTargetType.USER, UUID.randomUUID(), null, ""))
                .isInstanceOf(InvalidReportException.class);
    }

    @Test
    @DisplayName("로그 문자열에 상세 신고 내용을 노출하지 않는다")
    void redactsDescription() {
        assertThat(submission("비공개 신고 내용").toString()).doesNotContain("비공개 신고 내용");
    }

    private ReportSubmission submission(String description) {
        return new ReportSubmission(ReportTargetType.USER, UUID.randomUUID(), ReportReason.OTHER, description);
    }
}
