package com.harudle.feed.service.port;

import com.harudle.generation.diary.service.port.dto.ReferenceImage;

/** 공개 피드에서 확인된 저장 키로만 미리보기 원본을 읽는다. */
@FunctionalInterface
public interface FeedPreviewImageReader {

    ReferenceImage read(String imageObjectKey);
}
