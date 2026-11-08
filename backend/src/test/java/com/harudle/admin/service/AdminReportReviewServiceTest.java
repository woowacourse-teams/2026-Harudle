package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.admin.query.AdminReportSnapshot;
import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.repository.AdminReportRepository;
import com.harudle.admin.service.exception.AdminReportNotFoundException;
import com.harudle.admin.service.exception.AdminReportReviewConflictException;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportTargetType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminReportReviewServiceTest {

    private static final UUID REPORT = UUID.randomUUID();
    private static final UUID FIRST_ADMIN = UUID.randomUUID();
    private static final UUID SECOND_ADMIN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");

    @Mock
    private AdminReportRepository reports;

    private AdminReportReviewService service;

    @BeforeEach
    void setUp() {
        service = new AdminReportReviewService(reports, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(value = AdminReportStatus.class, names = {"DISMISSED", "RESOLVED"})
    @DisplayName("접수 신고를 잠근 뒤 종결 상태와 처리자 및 시각을 함께 저장한다")
    void completesReceivedReport(AdminReportStatus status) {
        var review = new AdminReportReview(status, " 조치 결과 ");
        var completed = snapshot(status, FIRST_ADMIN, NOW, "조치 결과");
        when(reports.findByIdForUpdate(REPORT))
                .thenReturn(Optional.of(snapshot(AdminReportStatus.RECEIVED, null, null, null)));
        when(reports.completeReview(REPORT, FIRST_ADMIN, review, NOW)).thenReturn(completed);

        assertThat(service.review(FIRST_ADMIN, REPORT, review)).isSameAs(completed);
        verify(reports).findByIdForUpdate(REPORT);
        verify(reports).completeReview(REPORT, FIRST_ADMIN, review, NOW);
    }

    @Test
    @DisplayName("같은 결정과 정규화 사유의 재시도는 최초 처리자와 시각을 그대로 반환한다")
    void replaysSameDecisionWithoutOverwritingAudit() {
        var originalTime = NOW.minusSeconds(20);
        var completed = snapshot(AdminReportStatus.RESOLVED, FIRST_ADMIN, originalTime, "처리 완료");
        when(reports.findByIdForUpdate(REPORT)).thenReturn(Optional.of(completed));

        assertThat(service.review(SECOND_ADMIN, REPORT,
                new AdminReportReview(AdminReportStatus.RESOLVED, "  처리 완료  "))).isSameAs(completed);
        verify(reports, never()).completeReview(any(), any(), any(), any());
    }

    @Test
    @DisplayName("처리자가 물리 삭제된 종결 신고도 같은 요청에는 기존 결과를 반환한다")
    void replaysReviewWithDeletedAdministrator() {
        var completed = snapshot(AdminReportStatus.RESOLVED, null, NOW, "처리 완료");
        when(reports.findByIdForUpdate(REPORT)).thenReturn(Optional.of(completed));
        assertThat(service.review(SECOND_ADMIN, REPORT,
                new AdminReportReview(AdminReportStatus.RESOLVED, "처리 완료"))).isSameAs(completed);
        verify(reports, never()).completeReview(any(), any(), any(), any());
    }

    @Test
    @DisplayName("종결된 신고의 결정이나 사유를 바꾸면 충돌로 거절한다")
    void rejectsDifferentTerminalDecisionOrReason() {
        when(reports.findByIdForUpdate(REPORT)).thenReturn(Optional.of(
                snapshot(AdminReportStatus.DISMISSED, FIRST_ADMIN, NOW, "위반 아님")));
        assertThatThrownBy(() -> service.review(SECOND_ADMIN, REPORT,
                new AdminReportReview(AdminReportStatus.RESOLVED, "위반 아님")))
                .isInstanceOf(AdminReportReviewConflictException.class);
        assertThatThrownBy(() -> service.review(SECOND_ADMIN, REPORT,
                new AdminReportReview(AdminReportStatus.DISMISSED, "다른 사유")))
                .isInstanceOf(AdminReportReviewConflictException.class);
        verify(reports, never()).completeReview(any(), any(), any(), any());
    }

    @Test
    @DisplayName("없는 신고는 처리하지 않고 조회 실패를 반환한다")
    void rejectsMissingReport() {
        when(reports.findByIdForUpdate(REPORT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.review(FIRST_ADMIN, REPORT,
                new AdminReportReview(AdminReportStatus.RESOLVED, "처리")))
                .isInstanceOf(AdminReportNotFoundException.class);
        verify(reports, never()).completeReview(any(), any(), any(), any());
    }

    @Test
    @DisplayName("감사 정보 저장 실패를 정상 종결로 바꾸지 않는다")
    void propagatesPersistenceFailure() {
        var review = new AdminReportReview(AdminReportStatus.RESOLVED, "처리");
        when(reports.findByIdForUpdate(REPORT)).thenReturn(Optional.of(
                snapshot(AdminReportStatus.RECEIVED, null, null, null)));
        var failure = new IllegalStateException("저장 실패");
        when(reports.completeReview(eq(REPORT), eq(FIRST_ADMIN), eq(review), eq(NOW))).thenThrow(failure);
        assertThatThrownBy(() -> service.review(FIRST_ADMIN, REPORT, review)).isSameAs(failure);
    }

    private static AdminReportSnapshot snapshot(AdminReportStatus status, UUID reviewer, Instant at, String reason) {
        return new AdminReportSnapshot(REPORT, null, ReportTargetType.FEED, UUID.randomUUID(), UUID.randomUUID(),
                ReportReason.SPAM, "신고 상세", status, NOW.minusSeconds(100), reviewer, at, reason);
    }
}
