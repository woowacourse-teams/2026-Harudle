package com.harudle.admin.service.exception;

public class AdminReportReviewConflictException extends RuntimeException {

    public AdminReportReviewConflictException() {
        super("이미 종결된 신고의 처리 결과를 변경할 수 없습니다.");
    }
}
