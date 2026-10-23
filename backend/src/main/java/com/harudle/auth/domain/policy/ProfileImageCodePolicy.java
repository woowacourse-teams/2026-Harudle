package com.harudle.auth.domain.policy;

public class ProfileImageCodePolicy {
    public static final int MIN_CODE = 1;
    public static final int MAX_CODE = 5;

    private ProfileImageCodePolicy() {
    }

    public static short validateAndConvert(int code) {
        if (code < MIN_CODE || code > MAX_CODE) {
            throw new IllegalArgumentException("프로필 이미지 코드는 1부터 5까지여야 합니다.");
        }

        return (short) code;
    }
}
