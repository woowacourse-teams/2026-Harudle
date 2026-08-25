package com.harudle.admin.repository;

public class AdminUsageCountOutOfRangeException extends RuntimeException {
    public AdminUsageCountOutOfRangeException() { super("오늘 사용 횟수는 오늘 생성 한도 범위 안에서 설정해야 합니다."); }
}
