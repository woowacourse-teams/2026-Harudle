package com.harudle.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.report.domain.InvalidReportException;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportSubmission;
import com.harudle.report.domain.ReportTargetType;
import com.harudle.report.repository.ReportRepository;
import com.harudle.report.repository.ReportSnapshot;
import com.harudle.report.repository.ReportTargetReader;
import com.harudle.report.service.exception.ReportTargetNotFoundException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final UUID REPORTER = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-22T01:00:00Z");

    @Mock private ReportRepository reports;
    @Mock private ReportTargetReader targets;
    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(reports, targets, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(ReportTargetType.class)
    @DisplayName("서버가 조회한 작성자와 인증된 신고자로 세 종류의 대상을 접수한다")
    void acceptsEachTargetAndUsesResolvedAuthor(ReportTargetType type) {
        when(reports.isActiveMember(REPORTER)).thenReturn(true);
        when(targets.findVisibleTargetAuthorId(REPORTER, type, TARGET)).thenReturn(Optional.of(AUTHOR));
        when(reports.insert(any())).thenReturn(true);

        ReportReceipt receipt = service.submit(REPORTER, submission(type));

        var captor = ArgumentCaptor.forClass(ReportSnapshot.class);
        verify(reports).insert(captor.capture());
        assertThat(captor.getValue().reporterId()).isEqualTo(REPORTER);
        assertThat(captor.getValue().targetUserId()).isEqualTo(AUTHOR);
        assertThat(captor.getValue().targetId()).isEqualTo(TARGET);
        assertThat(captor.getValue().createdAt()).isEqualTo(NOW);
        assertThat(receipt.id()).isEqualTo(captor.getValue().id());
        assertThat(receipt.duplicate()).isFalse();
    }

    @Test
    @DisplayName("비회원과 탈퇴한 신고자는 대상 조회나 기존 접수 확인 전에 거절한다")
    void rejectsInactiveReporterBeforeReadingTargets() {
        assertThatThrownBy(() -> service.submit(null, submission(ReportTargetType.USER)))
                .isInstanceOf(AuthenticationRequiredException.class);
        assertThatThrownBy(() -> service.submit(REPORTER, submission(ReportTargetType.USER)))
                .isInstanceOf(AuthenticationRequiredException.class);
        verify(reports, never()).findReceiptIdByReporterAndTarget(any(), any(), any());
        verifyNoInteractions(targets);
    }

    @Test
    @DisplayName("대상이 사라진 뒤에도 본인의 기존 접수를 반환하고 최초 사유를 변경하지 않는다")
    void acknowledgesPreviousReceiptWithoutReadingDeletedTarget() {
        UUID previousId = UUID.randomUUID();
        when(reports.isActiveMember(REPORTER)).thenReturn(true);
        when(reports.findReceiptIdByReporterAndTarget(REPORTER, ReportTargetType.FEED, TARGET))
                .thenReturn(Optional.of(previousId));

        ReportReceipt receipt = service.submit(REPORTER, submission(ReportTargetType.FEED));

        assertThat(receipt).isEqualTo(new ReportReceipt(previousId, true));
        verifyNoInteractions(targets);
        verify(reports, never()).insert(any());
    }

    @Test
    @DisplayName("본인과 본인 콘텐츠는 신고할 수 없다")
    void rejectsSelfReport() {
        when(reports.isActiveMember(REPORTER)).thenReturn(true);
        when(targets.findVisibleTargetAuthorId(REPORTER, ReportTargetType.COMMENT, TARGET))
                .thenReturn(Optional.of(REPORTER));
        assertThatThrownBy(() -> service.submit(REPORTER, submission(ReportTargetType.COMMENT)))
                .isInstanceOf(InvalidReportException.class);
        verify(reports, never()).insert(any());
    }

    @ParameterizedTest
    @EnumSource(ReportTargetType.class)
    @DisplayName("대상 조회에 실패해도 첫 조회 뒤 완료된 본인의 접수가 있으면 그 접수 번호를 반환한다")
    void acknowledgesReceiptCommittedBeforeTargetDisappeared(ReportTargetType type) {
        UUID winningId = UUID.randomUUID();
        when(reports.isActiveMember(REPORTER)).thenReturn(true);
        when(reports.findReceiptIdByReporterAndTarget(REPORTER, type, TARGET))
                .thenReturn(Optional.empty(), Optional.of(winningId));
        when(targets.findVisibleTargetAuthorId(REPORTER, type, TARGET)).thenReturn(Optional.empty());

        assertThat(service.submit(REPORTER, submission(type)))
                .isEqualTo(new ReportReceipt(winningId, true));

        var order = inOrder(reports, targets);
        order.verify(reports).isActiveMember(REPORTER);
        order.verify(reports).findReceiptIdByReporterAndTarget(REPORTER, type, TARGET);
        order.verify(targets).findVisibleTargetAuthorId(REPORTER, type, TARGET);
        order.verify(reports).findReceiptIdByReporterAndTarget(REPORTER, type, TARGET);
        verify(reports, never()).insert(any());
    }

    @ParameterizedTest
    @EnumSource(ReportTargetType.class)
    @DisplayName("공개 대상도 없고 재확인한 본인의 접수도 없으면 새 접수를 저장하지 않고 거절한다")
    void rejectsMissingTargetWithoutAnyReceipt(ReportTargetType type) {
        when(reports.isActiveMember(REPORTER)).thenReturn(true);

        assertThatThrownBy(() -> service.submit(REPORTER, submission(type)))
                .isInstanceOf(ReportTargetNotFoundException.class);

        var order = inOrder(reports, targets);
        order.verify(reports).isActiveMember(REPORTER);
        order.verify(reports).findReceiptIdByReporterAndTarget(REPORTER, type, TARGET);
        order.verify(targets).findVisibleTargetAuthorId(REPORTER, type, TARGET);
        order.verify(reports).findReceiptIdByReporterAndTarget(REPORTER, type, TARGET);
        verify(reports, never()).insert(any());
    }

    @Test
    @DisplayName("동시에 먼저 저장한 신고가 있으면 그 접수 ID를 반환한다")
    void returnsWinningReceiptAfterInsertConflict() {
        UUID previousId = UUID.randomUUID();
        when(reports.isActiveMember(REPORTER)).thenReturn(true);
        when(reports.findReceiptIdByReporterAndTarget(REPORTER, ReportTargetType.FEED, TARGET))
                .thenReturn(Optional.empty(), Optional.of(previousId));
        when(targets.findVisibleTargetAuthorId(REPORTER, ReportTargetType.FEED, TARGET))
                .thenReturn(Optional.of(AUTHOR));

        assertThat(service.submit(REPORTER, submission(ReportTargetType.FEED)))
                .isEqualTo(new ReportReceipt(previousId, true));
    }

    private ReportSubmission submission(ReportTargetType type) {
        return new ReportSubmission(type, TARGET, ReportReason.OTHER, "나중 요청의 사유");
    }
}
