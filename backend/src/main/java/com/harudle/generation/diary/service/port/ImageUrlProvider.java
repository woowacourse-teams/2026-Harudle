package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;

@FunctionalInterface
public interface ImageUrlProvider {

    ImageAccessUrl createAccessUrl(String imageObjectKey);

    /** 목록 응답 하나의 모든 이미지가 공유할 발급기. 요청 간 조회 예산을 공유하지 않는다. */
    default ImageUrlProvider forResponse() {
        return this;
    }
}
