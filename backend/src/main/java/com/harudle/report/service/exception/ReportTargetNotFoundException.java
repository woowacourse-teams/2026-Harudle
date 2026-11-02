package com.harudle.report.service.exception;

public final class ReportTargetNotFoundException extends RuntimeException {
    public ReportTargetNotFoundException() {
        super("신고할 대상을 찾을 수 없습니다.");
    }
}
