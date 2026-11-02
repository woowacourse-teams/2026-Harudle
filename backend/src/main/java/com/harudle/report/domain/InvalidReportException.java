package com.harudle.report.domain;

public final class InvalidReportException extends RuntimeException {
    public InvalidReportException() {
        super("신고 대상이나 내용이 올바르지 않습니다.");
    }
}
