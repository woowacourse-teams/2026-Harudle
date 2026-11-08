package com.harudle.admin.service.exception;

public class AdminReportNotFoundException extends RuntimeException {

    public AdminReportNotFoundException() {
        super("신고를 찾을 수 없습니다.");
    }
}
