package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;

@FunctionalInterface
public interface ImageUrlProvider {

    ImageAccessUrl createAccessUrl(String imageObjectKey);

    /** 피드 목록과 상세 조회에서 재사용할 이미지 발급기. */
    default ImageUrlProvider forFeed() {
        return this;
    }

    /** 생성 성공이 확인된 일기를 게시할 때는 저장소의 이미지 존재 여부를 다시 조회하지 않는다. */
    default ImageUrlProvider forPublication() {
        return this;
    }

    /** 목록 응답 하나의 모든 이미지가 공유할 발급기. 요청 간 조회 예산을 공유하지 않는다. */
    default ImageUrlProvider forResponse() {
        return this;
    }
}
