package com.harudle.diary.domain;

import java.util.UUID;

public interface DiarySourceTextCipher {

    int LEGACY_VERSION = 0;
    int CURRENT_VERSION = 1;

    String encrypt(UUID diaryId, UUID userId, String plaintext);

    String decrypt(UUID diaryId, UUID userId, String storedText, int version);

    /**
     * Protects a canonical SHA-256 request fingerprint with the key selected by the stored source-text version.
     * Ciphertext authentication remains the responsibility of {@link #decrypt(UUID, UUID, String, int)}.
     */
    String protectRequestFingerprint(UUID diaryId, UUID userId, String storedText, int version, String fingerprint);
}
