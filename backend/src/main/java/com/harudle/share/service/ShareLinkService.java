package com.harudle.share.service;

import com.harudle.generation.domain.GenerationStatus;
import com.harudle.generation.service.exception.GenerationInProgressException;
import com.harudle.share.domain.ShareLink;
import com.harudle.share.repository.ShareLinkRepository;
import com.harudle.share.service.exception.ShareGenerationFailedException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShareLinkService {

    private final ShareLinkCreationReader shareLinkCreationReader;
    private final ShareLinkRepository shareLinkRepository;

    public ShareLinkService(
            ShareLinkCreationReader shareLinkCreationReader,
            ShareLinkRepository shareLinkRepository
    ) {
        this.shareLinkCreationReader = shareLinkCreationReader;
        this.shareLinkRepository = shareLinkRepository;
    }

    @Transactional
    public ShareLinkCreationResult createOrGet(UUID userId, UUID diaryId) {
        ShareLinkCreationInfo creationInfo = shareLinkCreationReader.read(userId, diaryId);
        validateGenerationStatus(creationInfo);

        return shareLinkRepository.findByGenerationId(creationInfo.generationId())
                .map(shareLink -> toResult(shareLink, false))
                .orElseGet(() -> create(creationInfo.generationId()));
    }

    private ShareLinkCreationResult create(UUID generationId) {
        ShareLink shareLink = shareLinkRepository.saveAndFlush(ShareLink.create(generationId));
        return toResult(shareLink, true);
    }

    private void validateGenerationStatus(ShareLinkCreationInfo creationInfo) {
        if (creationInfo.generationStatus() == GenerationStatus.PROCESSING) {
            throw new GenerationInProgressException();
        }
        if (creationInfo.generationStatus() == GenerationStatus.FAILED) {
            throw new ShareGenerationFailedException();
        }
    }

    private ShareLinkCreationResult toResult(ShareLink shareLink, boolean created) {
        return new ShareLinkCreationResult(
                shareLink.getId(),
                shareLink.getCreatedAt(),
                created
        );
    }
}
