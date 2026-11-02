package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.service.exception.InvalidAdminReportReviewException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AdminReportReviewTest {

    @Test
    @DisplayName("종결 사유의 앞뒤 공백을 제거하고 이모지 500개를 허용한다")
    void normalizesReasonAndAcceptsUnicodeBoundary() {
        var review = new AdminReportReview(AdminReportStatus.RESOLVED, "  조치 완료  ");
        assertThat(review.reason()).isEqualTo("조치 완료");
        assertThat(new AdminReportReview(AdminReportStatus.DISMISSED, "😀".repeat(500)).reason())
                .hasSize(1000);
        assertThat(review.toString()).doesNotContain("조치 완료");
    }

    @ParameterizedTest
    @MethodSource("invalidReviews")
    @DisplayName("접수 상태나 빈 사유 및 잘못된 문자는 종결 명령으로 만들 수 없다")
    void rejectsInvalidReview(AdminReportStatus status, String reason) {
        assertThatThrownBy(() -> new AdminReportReview(status, reason))
                .isInstanceOf(InvalidAdminReportReviewException.class);
    }

    private static Stream<Arguments> invalidReviews() {
        return Stream.of(Arguments.of(null, "처리"), Arguments.of(AdminReportStatus.RECEIVED, "처리"),
                Arguments.of(AdminReportStatus.RESOLVED, null), Arguments.of(AdminReportStatus.RESOLVED, ""),
                Arguments.of(AdminReportStatus.RESOLVED, " \n\t "),
                Arguments.of(AdminReportStatus.RESOLVED, "😀".repeat(501)),
                Arguments.of(AdminReportStatus.RESOLVED, "사유\0"),
                Arguments.of(AdminReportStatus.RESOLVED, "사유\uD800"));
    }
}
