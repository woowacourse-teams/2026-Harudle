package com.harudle.diary.domain;

import java.util.UUID;

public interface DiarySourceTextCipher {

    int LEGACY_VERSION = 0;
    int CURRENT_VERSION = 1;

    String encrypt(UUID diaryId, UUID userId, String plaintext);

    String decrypt(UUID diaryId, UUID userId, String storedText, int version);

    /**
     * Protects a canonical SHA-256 request fingerprint with the stored v1 envelope's key.
     * Legacy plaintext and SHA passthrough are rejected after migration cutover.
     * Ciphertext authentication remains the responsibility of {@link #decrypt(UUID, UUID, String, int)}.
     */
    String protectRequestFingerprint(UUID diaryId, UUID userId, String storedText, int version, String fingerprint);
}
