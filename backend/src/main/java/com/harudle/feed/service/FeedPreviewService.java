package com.harudle.feed.service;

import com.harudle.feed.service.dto.FeedPreviewDetails;
import com.harudle.feed.service.dto.FeedPreviewMetadata;
import com.harudle.feed.service.port.FeedPreviewImageProvider;
import com.harudle.feed.service.port.FeedPreviewReader;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FeedPreviewService {

    private final FeedPreviewReader feeds;
    private final FeedPreviewImageProvider images;

    public FeedPreviewService(FeedPreviewReader feeds, FeedPreviewImageProvider images) {
        this.feeds = feeds;
        this.images = images;
    }

    public FeedPreviewMetadata metadata(UUID feedId) {
        FeedPreviewDetails feed = feeds.read(feedId);
        return new FeedPreviewMetadata(feed.authorNickname() + "님의 네컷만화 | 하루들",
                feed.categoryName() + "에서 공유한 네컷만화를 확인해 보세요.");
    }

    public ReferenceImage image(UUID feedId) {
        // 공개 조회의 DB 트랜잭션이 끝난 뒤 저장소 조회와 이미지 변환을 시작한다.
        FeedPreviewDetails feed = feeds.read(feedId);
        return images.provide(feed.imageObjectKey());
    }
}
