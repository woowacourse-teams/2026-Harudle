package com.harudle.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.admin.query.AdminGenerationHistoryPage;
import com.harudle.admin.repository.AdminGenerationHistoryQueryRepository;
import com.harudle.admin.service.exception.AdminGenerationHistoryDateRangeException;
import com.harudle.generation.domain.GenerationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminGenerationHistoryQueryServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate FROM = LocalDate.of(2026, 8, 6);
    private static final LocalDate TO = LocalDate.of(2026, 8, 7);

    @Mock
    private AdminGenerationHistoryQueryRepository generationHistoryQueryRepository;

    private AdminGenerationHistoryQueryService generationHistoryQueryService;

    @BeforeEach
    void setUp() {
        generationHistoryQueryService = new AdminGenerationHistoryQueryService(
                generationHistoryQueryRepository,
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), SERVICE_ZONE)
        );
    }

    @Test
    @DisplayName("날짜와 상태 필터를 저장소 조회 조건으로 변환한다")
    void convertsFiltersForRepository() {
        var pageable = PageRequest.of(1, 10);
        when(generationHistoryQueryRepository.search(
                USER_ID,
                GenerationStatus.FAILED,
                Instant.parse("2026-08-05T15:00:00Z"),
                Instant.parse("2026-08-07T15:00:00Z"),
                pageable
        )).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        AdminGenerationHistoryPage actual = generationHistoryQueryService.search(
                USER_ID,
                GenerationStatus.FAILED,
                FROM,
                TO,
                1,
                10
        );

        assertThat(actual.content()).isEmpty();
        assertThat(actual.totalElements()).isZero();
        verify(generationHistoryQueryRepository).search(
                USER_ID,
                GenerationStatus.FAILED,
                Instant.parse("2026-08-05T15:00:00Z"),
                Instant.parse("2026-08-07T15:00:00Z"),
                pageable
        );
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 날짜 범위 오류를 반환한다")
    void rejectsInvalidDateRange() {
        assertThatThrownBy(() -> generationHistoryQueryService.search(
                null,
                null,
                TO,
                FROM,
                0,
                20
        )).isInstanceOf(AdminGenerationHistoryDateRangeException.class);
    }
}
