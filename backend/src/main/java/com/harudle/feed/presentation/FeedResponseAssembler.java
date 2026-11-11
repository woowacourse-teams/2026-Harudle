package com.harudle.feed.presentation;

import com.harudle.feed.configuration.FeedUrlProperties;
import com.harudle.feed.service.dto.FeedPageResult;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.generation.diary.service.port.ImageLookupBudgetExceededException;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageStorageException.DiagnosticType;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.observability.RequestOperationTimings;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class FeedResponseAssembler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeedResponseAssembler.class);

    private final ImageUrlProvider imageUrlProvider;
    private final ZoneId serviceZoneId;
    private final FeedUrlProperties urls;

    FeedResponseAssembler(ImageUrlProvider imageUrlProvider, ZoneId serviceZoneId, FeedUrlProperties urls) {
        this.imageUrlProvider = imageUrlProvider;
        this.serviceZoneId = serviceZoneId;
        this.urls = urls;
    }

    FeedResponse toPublishResponse(FeedResult result) {
        ImageAccessUrl accessUrl = imageUrlProvider.forPublication().createAccessUrl(result.imageObjectKey());
        FeedResponse response = toResponse(result, accessUrl);
        RequestOperationTimings.returnedImages(1);
        return response;
    }

    FeedResponse toResponse(FeedResult result) {
        Map<DiagnosticType, List<UUID>> failures = new EnumMap<>(DiagnosticType.class);
        ImageAccessUrl accessUrl = readImageUrl(result, imageUrlProvider.forFeedRead(), failures);
        logReadFailures(failures);
        RequestOperationTimings.returnedImages(accessUrl == null ? 0 : 1);
        return toResponse(result, accessUrl);
    }

    private FeedResponse toResponse(FeedResult result, @Nullable ImageAccessUrl accessUrl) {
        return new FeedResponse(
                result.id(),
                new FeedResponse.Author(
                        result.author().id(), result.author().nickname(), result.author().profileImageUrl(),
                        result.author().profileImageUrlExpiresAt()
                ),
                new FeedResponse.Category(result.category().id(), result.category().name()),
                accessUrl == null ? null : accessUrl.url(),
                accessUrl == null ? null : accessUrl.expiresAt().atZone(serviceZoneId).toOffsetDateTime(),
                result.publishedAt().atZone(serviceZoneId).toOffsetDateTime(),
                result.likeCount(), result.commentCount(), result.likedByMe(), result.isMine(),
                urls.shareUrl(result.id())
        );
    }

    FeedPageResponse toResponse(FeedPageResult page) {
        ImageUrlProvider responseUrls = imageUrlProvider.forFeedRead();
        Map<DiagnosticType, List<UUID>> failures = new EnumMap<>(DiagnosticType.class);
        FeedPageResponse response = new FeedPageResponse(
                page.items().stream().map(item -> toResponse(item, readImageUrl(item, responseUrls, failures))).toList(),
                page.nextCursor(), page.hasNext());
        logReadFailures(failures);
        RequestOperationTimings.returnedImages((int) response.items().stream()
                .filter(item -> item.imageUrl() != null)
                .count());
        return response;
    }

    private @Nullable ImageAccessUrl readImageUrl(FeedResult result, ImageUrlProvider responseUrls,
            Map<DiagnosticType, List<UUID>> failures) {
        DiagnosticType reason;
        try {
            return responseUrls.createAccessUrl(result.imageObjectKey());
        } catch (ImageLookupBudgetExceededException exception) {
            reason = DiagnosticType.LOOKUP_BUDGET_EXHAUSTED;
        } catch (ImageStorageException exception) {
            reason = Objects.requireNonNullElse(exception.diagnosticType(), DiagnosticType.OTHER);
        }
        failures.computeIfAbsent(reason, ignored -> new ArrayList<>()).add(result.id());
        return null;
    }

    private void logReadFailures(Map<DiagnosticType, List<UUID>> failures) {
        failures.forEach((reason, feedIds) -> {
            var event = reason == DiagnosticType.REQUEST_VALIDATION_ERROR ? LOGGER.atError() : LOGGER.atWarn();
            event.log("event=feed_image_url_unavailable failureType={} feedIds={}", reason, feedIds);
        });
    }
}
