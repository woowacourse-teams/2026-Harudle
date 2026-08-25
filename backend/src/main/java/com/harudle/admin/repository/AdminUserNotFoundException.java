package com.harudle.admin.repository;

import java.util.UUID;

public class AdminUserNotFoundException extends RuntimeException {
    public AdminUserNotFoundException(UUID userId) { super("관리자 조회 대상 사용자를 찾을 수 없습니다: " + userId); }
}
