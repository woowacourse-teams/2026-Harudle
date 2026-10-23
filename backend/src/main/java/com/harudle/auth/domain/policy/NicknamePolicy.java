package com.harudle.auth.domain.policy;

import com.harudle.auth.domain.exception.InvalidNicknameException;
import java.text.Normalizer;
import java.util.regex.Pattern;

public final class NicknamePolicy {

    public static final int MIN_LENGTH = 1;
    public static final int MAX_LENGTH = 10;
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 ]+");

    private NicknamePolicy() {
    }

    public static String normalizeAndValidate(String rawNickname) {
        if (rawNickname == null || rawNickname.isBlank()) {
            throw new InvalidNicknameException("닉네임은 필수입니다.");
        }

        validateWhitespace(rawNickname);
        String normalized = Normalizer.normalize(rawNickname, Normalizer.Form.NFC);

        int length = normalized.codePointCount(0, normalized.length());

        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new InvalidNicknameException("닉네임은 1자 이상 10자 이하여야 합니다.");
        }

        if (!ALLOWED_CHARACTERS.matcher(normalized).matches()) {
            throw new InvalidNicknameException("닉네임은 한글, 영문, 숫자와 공백만 사용할 수 있습니다.");
        }

        return normalized;
    }

    private static void validateWhitespace(String rawNickname) {
        if (rawNickname.indexOf('\t') >= 0
                || rawNickname.indexOf('\n') >= 0
                || rawNickname.indexOf('\r') >= 0) {
            throw new InvalidNicknameException("닉네임에는 탭이나 줄바꿈을 사용할 수 없습니다.");
        }

        if (!rawNickname.equals(rawNickname.strip())) {
            throw new InvalidNicknameException("닉네임 앞뒤의 공백을 제거해 주세요.");
        }

        if (rawNickname.contains("  ")) {
            throw new InvalidNicknameException("닉네임에는 공백을 연속으로 사용할 수 없습니다.");
        }
    }
}
