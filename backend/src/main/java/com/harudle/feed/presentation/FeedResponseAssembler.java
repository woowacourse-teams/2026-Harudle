package com.harudle.feed.presentation;

import com.harudle.feed.configuration.FeedUrlProperties;
import com.harudle.feed.service.dto.FeedPageResult;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.generation.diary.service.port.ImageLookupBudgetExceededException;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.observability.RequestOperationTimings;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class FeedResponseAssembler {

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
        ImageAccessUrl accessUrl = readImageUrl(result, imageUrlProvider.forFeedRead());
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
        FeedPageResponse response = new FeedPageResponse(
                page.items().stream().map(item -> toResponse(item, readImageUrl(item, responseUrls))).toList(),
                page.nextCursor(), page.hasNext());
        RequestOperationTimings.returnedImages((int) response.items().stream()
                .filter(item -> item.imageUrl() != null)
                .count());
        return response;
    }

    private @Nullable ImageAccessUrl readImageUrl(FeedResult result, ImageUrlProvider responseUrls) {
        try {
            return responseUrls.createAccessUrl(result.imageObjectKey());
        } catch (ImageLookupBudgetExceededException | ImageStorageException exception) {
            return null;
        }
    }
}
