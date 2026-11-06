package com.harudle.support;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.infrastructure.crypto.AesGcmDiarySourceTextCipher;
import com.harudle.diary.infrastructure.crypto.DiarySourceTextEncryptionProperties;
import java.util.Map;

public final class DiarySourceTextTestSupport {

    private DiarySourceTextTestSupport() {
    }

    public static DiarySourceTextCipher cipher() {
        return new AesGcmDiarySourceTextCipher(new DiarySourceTextEncryptionProperties(
                "primary", Map.of("primary", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
        ));
    }
}
