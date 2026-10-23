package com.harudle.generation.diary.service.port;

/** 객체 부재나 저장소 장애와 구분하는 조회 예산 소진 신호. */
public final class ImageLookupBudgetExceededException extends RuntimeException {
    public ImageLookupBudgetExceededException() {
        super("이미지 저장소 조회 시간 예산이 소진되었습니다.");
    }
}
