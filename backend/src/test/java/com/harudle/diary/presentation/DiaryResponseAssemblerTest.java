package com.harudle.diary.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryStreakDayResult;
import com.harudle.diary.service.dto.DiaryStreakResult;
import com.harudle.diary.service.dto.DiarySummaryResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.generation.adapter.out.s3.R2FallbackImageUrlProvider;
import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.ImageLookupBudget;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

class DiaryResponseAssemblerTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void thirtyImagesAcrossDaysShareOneBudgetAndNextResponseGetsFreshBudget(boolean streak) {
        AtomicLong time = new AtomicLong();
        ImageStorage source = mock(ImageStorage.class);
        BackupObjectStorage backup = mock(BackupObjectStorage.class);
        ImageUrlProvider primary = key -> new ImageAccessUrl(URI.create("https://source.example/" + key), Instant.MAX);
        S3StorageProperties s3 = new S3StorageProperties("source", "ap-northeast-2", "dev",
                "harudle/generated/diary-images/dev", "harudle/references/generation/dev",
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
        R2StorageProperties r2 = new R2StorageProperties(true, "dev", URI.create("https://backup.example"),
                "backup", "key", "secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        when(source.exists(anyString(), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            ImageLookupBudget budget = invocation.getArgument(1);
            assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(2));
            time.addAndGet(Duration.ofSeconds(2).toNanos());
            throw new ImageStorageException("timeout", null, ImageStorageException.DiagnosticType.CLIENT_ERROR);
        });
        DiaryResponseAssembler assembler = new DiaryResponseAssembler(
                new R2FallbackImageUrlProvider(primary, source, backup, s3, r2, time::get), ZoneId.of("Asia/Seoul"));
        List<DiaryDayResult> days = IntStream.range(0, 3).mapToObj(day -> new DiaryDayResult(
                LocalDate.of(2026, 10, day + 1), IntStream.range(0, 10).mapToObj(item -> {
                    int id = day * 10 + item;
                    return new DiarySummaryResult(new UUID(0, id), "diary-" + id,
                            "harudle/generated/diary-images/dev/" + id + "/image-960.webp");
                }).toList())).toList();

        List<DiarySummaryResponse> items = assemble(assembler, days, streak);
        assertThat(items).hasSize(30);
        assertThat(items).extracting(DiarySummaryResponse::id)
                .containsExactlyElementsOf(days.stream().flatMap(day -> day.items().stream()).map(DiarySummaryResult::id).toList());
        assertThat(items).allSatisfy(item -> assertThat(item.thumbnailUrl())
                .isEqualTo("https://source.example/harudle/generated/diary-images/dev/" + item.id().getLeastSignificantBits()
                        + "/image-240.webp"));
        verify(source).exists(anyString(), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(source);
        verifyNoInteractions(backup);

        assertThat(assemble(assembler, days, streak)).hasSize(30);
        verify(source, times(2)).exists(anyString(), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(source);
        verifyNoInteractions(backup);
    }

    private List<DiarySummaryResponse> assemble(DiaryResponseAssembler assembler, List<DiaryDayResult> days, boolean streak) {
        if (streak) {
            DiaryStreakResponse response = assembler.toStreakResponse(new DiaryStreakResult(true,
                    days.stream().map(day -> new DiaryStreakDayResult(day.date(), day.items())).toList()));
            assertThat(response.days()).extracting(DiaryStreakDayResponse::date)
                    .containsExactlyElementsOf(days.stream().map(DiaryDayResult::date).toList());
            assertThat(response.streakCount()).isEqualTo(3);
            assertThat(response.recordedToday()).isTrue();
            return response.days().stream().flatMap(day -> day.items().stream()).toList();
        }
        DiaryTimelineResponse response = assembler.toTimelineResponse(new DiaryTimelineResult(2026, 10, days));
        assertThat(response.days()).extracting(DiaryDayResponse::date)
                .containsExactlyElementsOf(days.stream().map(DiaryDayResult::date).toList());
        assertThat(response.year()).isEqualTo(2026);
        assertThat(response.month()).isEqualTo(10);
        return response.days().stream().flatMap(day -> day.items().stream()).toList();
    }
}
