package com.harudle.legal.application;

public class LegalDocumentNotFoundException extends RuntimeException {
    public LegalDocumentNotFoundException() {
        super("요청한 약관 문서를 찾을 수 없습니다.");
    }
}
