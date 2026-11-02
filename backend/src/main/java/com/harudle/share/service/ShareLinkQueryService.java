package com.harudle.share.service;

import com.harudle.share.repository.PublicShareSnapshot;
import com.harudle.share.repository.ShareLinkRepository;
import com.harudle.share.service.exception.ShareNotFoundException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShareLinkQueryService {

    private final ShareLinkRepository shareLinkRepository;

    public ShareLinkQueryService(ShareLinkRepository shareLinkRepository) {
        this.shareLinkRepository = shareLinkRepository;
    }

    @Transactional(readOnly = true)
    public PublicShareResult getPublicShare(UUID shareId, @Nullable UUID viewerId) {
        PublicShareSnapshot snapshot = shareLinkRepository.findPublicSnapshotById(shareId, viewerId)
                .orElseThrow(ShareNotFoundException::new);
        return new PublicShareResult(
                snapshot.title(),
                snapshot.diaryDate(),
                snapshot.imageObjectKey(),
                snapshot.diaryCreatedAt()
        );
    }
}
