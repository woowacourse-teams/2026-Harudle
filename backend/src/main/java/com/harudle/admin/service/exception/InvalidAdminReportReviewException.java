package com.harudle.admin.service.exception;

public class InvalidAdminReportReviewException extends RuntimeException {

    public InvalidAdminReportReviewException() {
        super("신고 처리 상태와 사유를 확인해 주세요.");
    }
}
