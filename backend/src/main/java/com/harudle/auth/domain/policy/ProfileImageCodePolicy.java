package com.harudle.auth.domain.policy;

public class ProfileImageCodePolicy {
    public static final int MIN_CODE = 1;
    public static final int MAX_CODE = 10;

    private ProfileImageCodePolicy() {
    }

    public static short validateAndConvert(int code) {
        if (code < MIN_CODE || code > MAX_CODE) {
            throw new IllegalArgumentException(
                    "프로필 이미지 코드는 %d부터 %d까지여야 합니다.".formatted(MIN_CODE, MAX_CODE)
            );
        }

        return (short) code;
    }
}
