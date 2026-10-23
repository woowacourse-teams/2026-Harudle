package com.harudle.generation.diary.domain;

import com.harudle.common.validation.TextValidator;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ImageObjectKeyPolicy {

    public static final int MAX_UTF8_BYTES = 1024;

    private ImageObjectKeyPolicy() {
    }

    public static String normalizeRequired(String value, String fieldName) {
        String normalizedValue = TextValidator.normalizeRequired(
                value,
                fieldName + "는 필수입니다."
        );
        int byteLength = normalizedValue.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    String.format(
                            Locale.ROOT,
                            "%s는 UTF-8 기준 %,d바이트 이하여야 합니다.",
                            fieldName,
                            MAX_UTF8_BYTES
                    )
            );
        }
        return normalizedValue;
    }
}
