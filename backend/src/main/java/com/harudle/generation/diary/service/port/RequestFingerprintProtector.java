package com.harudle.generation.diary.service.port;

import java.util.UUID;

public interface RequestFingerprintProtector {

    String protect(UUID diaryId, UUID userId, String fingerprint);
}
