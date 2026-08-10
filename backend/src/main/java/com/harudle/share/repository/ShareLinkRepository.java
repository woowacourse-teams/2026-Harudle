package com.harudle.share.repository;

import java.util.UUID;

public interface ShareLinkRepository {

    int deleteByDiaryId(UUID diaryId);
}
