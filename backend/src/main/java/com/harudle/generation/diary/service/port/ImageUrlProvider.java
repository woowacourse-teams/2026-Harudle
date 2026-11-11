package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;

@FunctionalInterface
public interface ImageUrlProvider {

    ImageAccessUrl createAccessUrl(String imageObjectKey);

    /** 피드 목록, 상세와 일기 월간 조회의 응답 하나 안에서만 저장소 선택을 공유하는 이미지 발급기. */
    default ImageUrlProvider forRead() {
        return this;
    }

    /** 피드 목록 또는 상세 응답에 쓰는 발급기. 피드 조회의 S3 주소만 재사용할 수 있다. */
    default ImageUrlProvider forFeedRead() {
        return forRead();
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
