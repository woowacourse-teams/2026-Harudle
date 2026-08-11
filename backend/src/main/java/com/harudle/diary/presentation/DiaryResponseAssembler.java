package com.harudle.diary.presentation;

import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.diary.service.dto.DiarySummaryResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.generation.service.port.ImageAccessUrl;
import com.harudle.generation.service.port.ImageUrlProvider;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class DiaryResponseAssembler {

    private final ImageUrlProvider imageUrlProvider;
    private final ZoneId serviceZoneId;

    DiaryResponseAssembler(ImageUrlProvider imageUrlProvider, ZoneId serviceZoneId) {
        this.imageUrlProvider = imageUrlProvider;
        this.serviceZoneId = serviceZoneId;
    }

    DiaryDetailResponse toDetailResponse(DiaryDetailResult result) {
        return new DiaryDetailResponse(
                result.id(),
                result.diaryDate(),
                result.sourceText(),
                toServiceTime(result.createdAt()),
                toGenerationResponse(result.generation())
        );
    }

    DiaryTimelineResponse toTimelineResponse(DiaryTimelineResult result) {
        List<DiaryDayResponse> days = result.days().stream()
                .map(this::toDayResponse)
                .toList();
        return new DiaryTimelineResponse(result.year(), result.month(), days);
    }

    private DiaryDayResponse toDayResponse(DiaryDayResult result) {
        List<DiarySummaryResponse> items = result.items().stream()
                .map(this::toSummaryResponse)
                .toList();
        return new DiaryDayResponse(result.date(), result.hasItems(), items);
    }

    private DiarySummaryResponse toSummaryResponse(DiarySummaryResult result) {
        ImageAccessUrl imageAccessUrl = createImageAccessUrl(result.imageObjectKey());
        return new DiarySummaryResponse(
                result.id(),
                result.title(),
                imageAccessUrl.url().toString()
        );
    }

    private DiaryGenerationResponse toGenerationResponse(DiaryGenerationResult result) {
        if (result.imageObjectKey() == null) {
            return new DiaryGenerationResponse(
                    result.id(),
                    result.status(),
                    result.title(),
                    null,
                    null,
                    toServiceTime(result.completedAt())
            );
        }
        ImageAccessUrl imageAccessUrl = createImageAccessUrl(result.imageObjectKey());
        return new DiaryGenerationResponse(
                result.id(),
                result.status(),
                result.title(),
                imageAccessUrl.url().toString(),
                toServiceTime(imageAccessUrl.expiresAt()),
                toServiceTime(result.completedAt())
        );
    }

    private ImageAccessUrl createImageAccessUrl(String imageObjectKey) {
        return imageUrlProvider.createAccessUrl(imageObjectKey);
    }

    private OffsetDateTime toServiceTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.atZone(serviceZoneId).toOffsetDateTime();
    }
}
