package com.harudle.feed.service.port;

import com.harudle.generation.diary.service.port.dto.ReferenceImage;

/** 공개 피드에서 확인한 저장 키를 최종 미리보기 JPEG로 제공한다. */
@FunctionalInterface
public interface FeedPreviewImageProvider {

    ReferenceImage provide(String imageObjectKey);
}
