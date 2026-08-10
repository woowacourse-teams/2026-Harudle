package com.harudle.diary.presentation;

import static com.harudle.common.config.TimeConfiguration.SERVICE_ZONE_ID;

import com.harudle.diary.service.dto.CreateDiaryResult;
import com.harudle.diary.service.dto.DiaryDayResult;
import com.harudle.diary.service.dto.DiaryDetailResult;
import com.harudle.diary.service.dto.DiaryGenerationResult;
import com.harudle.diary.service.dto.DiarySummaryResult;
import com.harudle.diary.service.dto.DiaryTimelineResult;
import com.harudle.generation.presentation.GenerationUsageResponse;
import com.harudle.generation.service.exception.GenerationUnavailableException;
import com.harudle.generation.service.port.ImageAccessUrl;
import com.harudle.generation.service.port.ImageUrlProvider;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class DiaryResponseAssembler {

    private final ObjectProvider<ImageUrlProvider> imageUrlProvider;

    public DiaryResponseAssembler(ObjectProvider<ImageUrlProvider> imageUrlProvider) {
        this.imageUrlProvider = imageUrlProvider;
    }

    public CreateDiaryResponse toCreateResponse(CreateDiaryResult result) {
        return new CreateDiaryResponse(
                result.id(),
                result.diaryDate(),
                result.sourceText(),
                toServiceTime(result.createdAt()),
                toGenerationResponse(result.generation()),
                GenerationUsageResponse.from(result.usage())
        );
    }

    public DiaryDetailResponse toDetailResponse(DiaryDetailResult result) {
        return new DiaryDetailResponse(
                result.id(),
                result.diaryDate(),
                result.sourceText(),
                toServiceTime(result.createdAt()),
                toGenerationResponse(result.generation())
        );
    }

    public DiaryTimelineResponse toTimelineResponse(DiaryTimelineResult result) {
        List<DiaryDayResponse> days = result.days().stream()
                .map(this::toDayResponse)
                .toList();
        return new DiaryTimelineResponse(result.year(), result.month(), days);
    }

    private DiaryDayResponse toDayResponse(DiaryDayResult result) {
        List<DiarySummaryResponse> items = result.items().stream()
                .map(this::toSummaryResponse)
                .toList();
        return new DiaryDayResponse(result.date(), result.exist(), items);
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
        ImageUrlProvider provider = imageUrlProvider.getIfAvailable();
        if (provider == null) {
            throw new GenerationUnavailableException("이미지 URL 발급 어댑터가 구성되지 않았습니다.");
        }
        return provider.createAccessUrl(imageObjectKey);
    }

    private OffsetDateTime toServiceTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.atZone(SERVICE_ZONE_ID).toOffsetDateTime();
    }
}
