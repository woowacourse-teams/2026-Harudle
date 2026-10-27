package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.admin.query.AdminReportPage;
import com.harudle.admin.query.AdminReportSnapshot;
import com.harudle.admin.query.AdminReportStatus;
import com.harudle.admin.query.AdminReportTarget;
import com.harudle.admin.repository.AdminReportRepository;
import com.harudle.admin.service.exception.AdminReportNotFoundException;
import com.harudle.report.domain.ReportReason;
import com.harudle.report.domain.ReportTargetType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class AdminReportQueryServiceTest {

    @Mock
    private AdminReportRepository reports;

    @InjectMocks
    private AdminReportQueryService service;

    @Test
    @DisplayName("선택한 신고 상태와 페이지 조건을 저장소에 전달한다")
    void forwardsStatusAndPagination() {
        var result = new AdminReportPage(List.of(), 0);
        when(reports.search(AdminReportStatus.DISMISSED, PageRequest.of(2, 10))).thenReturn(result);
        assertThat(service.search(AdminReportStatus.DISMISSED, 2, 10)).isSameAs(result);
    }

    @Test
    @DisplayName("신고자와 원본이 물리 삭제되어도 신고와 원본 없음 상태를 반환한다")
    void findsReportWhenReporterAndTargetWereDeleted() {
        UUID reportId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        var report = new AdminReportSnapshot(reportId, null, ReportTargetType.COMMENT, targetId,
                UUID.randomUUID(), ReportReason.HARASSMENT, "상세", AdminReportStatus.RECEIVED,
                Instant.EPOCH, null, null, null);
        when(reports.findById(reportId)).thenReturn(Optional.of(report));
        when(reports.findTarget(ReportTargetType.COMMENT, targetId)).thenReturn(AdminReportTarget.missing());

        var result = service.findDetail(reportId);
        assertThat(result.report().reporterId()).isNull();
        assertThat(result.target().exists()).isFalse();
        verify(reports).findTarget(ReportTargetType.COMMENT, targetId);
        assertThat(result.toString()).doesNotContain("상세");
    }

    @Test
    @DisplayName("없는 신고의 상세는 조회 실패로 반환한다")
    void rejectsMissingReport() {
        UUID id = UUID.randomUUID();
        when(reports.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findDetail(id)).isInstanceOf(AdminReportNotFoundException.class);
    }
}
