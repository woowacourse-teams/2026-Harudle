package com.harudle.generation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.repository.GenerationUsageRepository;
import com.harudle.generation.service.exception.DailyGenerationLimitExceededException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GenerationUsageServiceTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final ZoneId SERVICE_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-08-06T14:59:59Z");
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 8, 6);

    @Mock
    private GenerationUsageRepository generationUsageRepository;

    private GenerationUsageService generationUsageService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, SERVICE_ZONE_ID);
        generationUsageService = new GenerationUsageService(generationUsageRepository, clock);
    }

    @Test
    @DisplayName("KST 기준 오늘의 생성 사용량을 조회한다")
    void getTodayUsage() {
        GenerationUsage expected = new GenerationUsage(USAGE_DATE, 2, 3);
        when(generationUsageRepository.find(USER_ID, USAGE_DATE)).thenReturn(Optional.of(expected));

        GenerationUsage actual = generationUsageService.getTodayUsage(USER_ID);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("오늘의 생성 사용 기록이 없으면 빈 사용량을 반환한다")
    void getTodayUsageReturnsEmptyUsage() {
        when(generationUsageRepository.find(USER_ID, USAGE_DATE)).thenReturn(Optional.empty());

        GenerationUsage actual = generationUsageService.getTodayUsage(USER_ID);

        assertThat(actual).isEqualTo(GenerationUsage.empty(USAGE_DATE));
    }

    @Test
    @DisplayName("일일 생성 한도 안에서 사용량을 원자적으로 증가시킨다")
    void incrementTodayUsage() {
        GenerationUsage expected = new GenerationUsage(USAGE_DATE, 3, 3);
        when(generationUsageRepository.tryIncrementWithinLimit(USER_ID, USAGE_DATE))
                .thenReturn(Optional.of(expected));

        GenerationUsage actual = generationUsageService.incrementTodayUsage(USER_ID);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("일일 생성 한도를 초과하면 다음 KST 자정까지 재시도할 수 없다")
    void incrementTodayUsageRejectsExceededLimit() {
        when(generationUsageRepository.tryIncrementWithinLimit(USER_ID, USAGE_DATE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> generationUsageService.incrementTodayUsage(USER_ID))
                .isInstanceOfSatisfying(
                        DailyGenerationLimitExceededException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(1L)
                );
    }

    @Test
    @DisplayName("증가 시도 중 자정이 지나도 시도한 사용일의 다음 자정 기준으로 재시도 시간을 계산한다")
    void calculateRetryAfterFromAttemptedUsageDate() {
        Instant beforeMidnight = Instant.parse("2026-08-06T14:59:59Z");
        Instant afterMidnight = Instant.parse("2026-08-06T15:00:00Z");
        Clock crossingMidnightClock = mock(Clock.class);
        when(crossingMidnightClock.getZone()).thenReturn(SERVICE_ZONE_ID);
        when(crossingMidnightClock.instant())
                .thenReturn(beforeMidnight, afterMidnight, afterMidnight);
        GenerationUsageService service = new GenerationUsageService(
                generationUsageRepository,
                crossingMidnightClock
        );
        when(generationUsageRepository.tryIncrementWithinLimit(USER_ID, USAGE_DATE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.incrementTodayUsage(USER_ID))
                .isInstanceOfSatisfying(
                        DailyGenerationLimitExceededException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(1L)
                );
    }
}
