package com.harudle.feed.presentation;

import com.harudle.feed.configuration.FeedUrlProperties;
import com.harudle.feed.service.dto.FeedPageResult;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.time.ZoneId;
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

    FeedResponse toResponse(FeedResult result) {
        // 저장 트랜잭션이 끝난 뒤 기존 발급기를 사용해 S3/R2 접근 URL을 만든다.
        ImageAccessUrl accessUrl = imageUrlProvider.createAccessUrl(result.imageObjectKey());
        return new FeedResponse(
                result.id(),
                new FeedResponse.Author(
                        result.author().id(), result.author().nickname(), result.author().profileImageUrl()
                ),
                new FeedResponse.Category(result.category().id(), result.category().name()),
                accessUrl.url(),
                accessUrl.expiresAt().atZone(serviceZoneId).toOffsetDateTime(),
                result.publishedAt().atZone(serviceZoneId).toOffsetDateTime(),
                result.likeCount(), result.commentCount(), result.likedByMe(), result.isMine(),
                urls.shareUrl(result.id())
        );
    }

    FeedPageResponse toResponse(FeedPageResult page) {
        return new FeedPageResponse(page.items().stream().map(this::toResponse).toList(),
                page.nextCursor(), page.hasNext());
    }
}
