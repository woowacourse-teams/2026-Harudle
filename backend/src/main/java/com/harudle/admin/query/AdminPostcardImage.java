package com.harudle.admin.query;

import org.jspecify.annotations.Nullable;

/** 엽서 인쇄에 쓸 이미지의 저장소 키와 만화 제목. 키는 응답에 노출하지 않고 접근 주소 발급에만 쓴다. */
public record AdminPostcardImage(String imageObjectKey, @Nullable String title) {
}
