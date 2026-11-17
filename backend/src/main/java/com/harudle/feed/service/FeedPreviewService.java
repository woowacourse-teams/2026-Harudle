package com.harudle.feed.service;

import com.harudle.feed.service.dto.FeedPreviewMetadata;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.feed.service.port.FeedPreviewImageReader;
import com.harudle.feed.service.port.FeedPreviewImageRenderer;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class FeedPreviewService {

    private final FeedQueryService feeds;
    private final FeedPreviewImageReader images;
    private final FeedPreviewImageRenderer renderer;
    private final Semaphore imageRequests = new Semaphore(2);

    public FeedPreviewService(FeedQueryService feeds, FeedPreviewImageReader images, FeedPreviewImageRenderer renderer) {
        this.feeds = feeds;
        this.images = images;
        this.renderer = renderer;
    }

    public FeedPreviewMetadata metadata(UUID feedId) {
        FeedResult feed = feeds.getDetail(null, feedId);
        return new FeedPreviewMetadata(feed.author().nickname() + "님의 네컷만화 | 하루들",
                feed.category().name() + "에서 공유한 네컷만화를 확인해 보세요.");
    }

    public ReferenceImage image(UUID feedId) {
        // 공개 조회의 DB 트랜잭션이 끝난 뒤 저장소 조회와 이미지 변환을 시작한다.
        FeedResult feed = feeds.getDetail(null, feedId);
        if (!imageRequests.tryAcquire()) {
            throw new ImageStorageException("공유 미리보기 이미지 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        try {
            return renderer.render(images.read(feed.imageObjectKey()));
        } finally {
            imageRequests.release();
        }
    }
}
