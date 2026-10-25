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
import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

class DiaryResponseAssemblerTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eachMonthlyTimelineAndFeedReadSelectsStorageOnce(boolean s3Available) {
        ImageStorage source = mock(ImageStorage.class);
        BackupObjectStorage backup = mock(BackupObjectStorage.class);
        ImageUrlProvider primary = key -> new ImageAccessUrl(URI.create("https://source.example/" + key), Instant.MAX);
        S3StorageProperties s3 = new S3StorageProperties("source", "ap-northeast-2", "dev",
                "harudle/generated/diary-images/dev", "harudle/references/generation/dev",
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
        R2StorageProperties r2 = new R2StorageProperties(true, "dev", URI.create("https://backup.example"),
                "backup", "key", "secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        when(source.exists(anyString(), any(ImageLookupBudget.class))).thenReturn(s3Available);
        when(backup.findMetadata(anyString(), any(ImageLookupBudget.class))).thenAnswer(invocation -> Optional.of(
                new BackupObjectMetadata(invocation.getArgument(0), MediaType.IMAGE_PNG, 123, null)));
        when(backup.createAccessUrl(anyString())).thenAnswer(invocation -> new ImageAccessUrl(
                URI.create("https://backup.example/" + invocation.getArgument(0)), Instant.MAX));
        ImageUrlProvider provider = new R2FallbackImageUrlProvider(primary, source, backup, s3, r2, () -> 0);
        DiaryResponseAssembler assembler = new DiaryResponseAssembler(provider, ZoneId.of("Asia/Seoul"));

        assertThat(assembler.toTimelineResponse(new DiaryTimelineResult(2026, 9, List.of())).days()).isEmpty();
        verifyNoInteractions(source, backup);

        String selectedBase = s3Available ? "https://source.example/" : "https://backup.example/";
        for (int month : new int[]{10, 11}) {
            List<DiaryDayResult> days = IntStream.rangeClosed(1, 30).mapToObj(day -> new DiaryDayResult(
                    LocalDate.of(2026, month, day), List.of(new DiarySummaryResult(new UUID(month, day), "diary-" + day,
                            "harudle/generated/diary-images/dev/" + month + "-" + day + "/image-960.webp")))).toList();
            DiaryTimelineResponse response = assembler.toTimelineResponse(new DiaryTimelineResult(2026, month, days));
            assertThat(response.year()).isEqualTo(2026);
            assertThat(response.month()).isEqualTo(month);
            assertThat(response.days()).extracting(DiaryDayResponse::date)
                    .containsExactlyElementsOf(days.stream().map(DiaryDayResult::date).toList());
            assertThat(response.days()).allSatisfy(day -> {
                int date = day.date().getDayOfMonth();
                assertThat(day.items()).singleElement().satisfies(item -> {
                    assertThat(item.id()).isEqualTo(new UUID(month, date));
                    assertThat(item.thumbnailUrl()).isEqualTo(selectedBase
                            + "harudle/generated/diary-images/dev/" + month + "-" + date
                            + (s3Available ? "/image-240.webp" : "/image.png"));
                });
            });
        }
        String feedImage = "harudle/generated/diary-images/dev/feed/image-960.webp";
        assertThat(provider.forRead().createAccessUrl(feedImage).url().toString()).isEqualTo(selectedBase
                + (s3Available ? feedImage : feedImage.replace("image-960.webp", "image.png")));
        verify(source, times(3)).exists(anyString(), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(source);
        if (s3Available) {
            verifyNoInteractions(backup);
        } else {
            verify(backup, times(61)).findMetadata(anyString(), any(ImageLookupBudget.class));
            verify(backup, times(61)).createAccessUrl(anyString());
            verifyNoMoreInteractions(backup);
        }
    }

    @Test
    void thirtyStreakImagesAcrossDaysShareOneBudgetAndNextResponseGetsFreshBudget() {
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

        List<DiarySummaryResponse> items = assembleStreak(assembler, days);
        assertThat(items).hasSize(30);
        assertThat(items).extracting(DiarySummaryResponse::id)
                .containsExactlyElementsOf(days.stream().flatMap(day -> day.items().stream()).map(DiarySummaryResult::id).toList());
        assertThat(items).allSatisfy(item -> assertThat(item.thumbnailUrl())
                .isEqualTo("https://source.example/harudle/generated/diary-images/dev/" + item.id().getLeastSignificantBits()
                        + "/image-240.webp"));
        verify(source).exists(anyString(), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(source);
        verifyNoInteractions(backup);

        assertThat(assembleStreak(assembler, days)).hasSize(30);
        verify(source, times(2)).exists(anyString(), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(source);
        verifyNoInteractions(backup);
    }

    private List<DiarySummaryResponse> assembleStreak(DiaryResponseAssembler assembler, List<DiaryDayResult> days) {
        DiaryStreakResponse response = assembler.toStreakResponse(new DiaryStreakResult(true,
                days.stream().map(day -> new DiaryStreakDayResult(day.date(), day.items())).toList()));
        assertThat(response.days()).extracting(DiaryStreakDayResponse::date)
                .containsExactlyElementsOf(days.stream().map(DiaryDayResult::date).toList());
        assertThat(response.streakCount()).isEqualTo(3);
        assertThat(response.recordedToday()).isTrue();
        return response.days().stream().flatMap(day -> day.items().stream()).toList();
    }
}
