package com.harudle.feed.service.port;

import com.harudle.generation.diary.service.port.dto.ReferenceImage;

/** 저장소 이미지를 공유 미리보기용 JPEG로 변환한다. */
@FunctionalInterface
public interface FeedPreviewImageRenderer {

    ReferenceImage render(ReferenceImage source);
}
