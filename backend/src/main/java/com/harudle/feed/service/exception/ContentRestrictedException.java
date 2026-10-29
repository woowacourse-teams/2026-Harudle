package com.harudle.feed.service.exception;

public class ContentRestrictedException extends RuntimeException {
    public ContentRestrictedException() {
        super("공개가 제한된 콘텐츠입니다.");
    }
}
