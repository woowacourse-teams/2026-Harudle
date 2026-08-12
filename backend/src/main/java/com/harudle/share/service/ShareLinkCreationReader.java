package com.harudle.share.service;

import java.util.UUID;

public interface ShareLinkCreationReader {

    ShareLinkCreationInfo read(UUID userId, UUID diaryId);
}
