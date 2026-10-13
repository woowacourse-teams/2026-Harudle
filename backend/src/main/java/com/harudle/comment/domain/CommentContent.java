package com.harudle.comment.domain;

import com.harudle.common.validation.TextValidator;

/** 댓글은 일반 텍스트로 보존하며, 앞뒤 공백만 제거한 코드 포인트 길이를 검증한다. */
public final class CommentContent {

    public static final int MAX_CODE_POINTS = 100;
    private static final String VALIDATION_MESSAGE = "댓글은 1자 이상 100자 이하로 입력해주세요.";

    private CommentContent() {
    }

    public static String normalize(String value) {
        String content = TextValidator.normalizeRequired(value, VALIDATION_MESSAGE);
        if (content.indexOf('\0') >= 0 || hasUnpairedSurrogate(content)) {
            throw new IllegalArgumentException("댓글에 사용할 수 없는 문자가 포함되어 있습니다.");
        }
        int length = content.codePointCount(0, content.length());
        if (length < 1 || length > MAX_CODE_POINTS) {
            throw new IllegalArgumentException(VALIDATION_MESSAGE);
        }
        return content;
    }

    private static boolean hasUnpairedSurrogate(String content) {
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == content.length() || !Character.isLowSurrogate(content.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return true;
            }
        }
        return false;
    }
}
