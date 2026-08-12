package com.harudle.share.repository;

import com.harudle.share.domain.ShareLink;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShareLinkRepository extends
        JpaRepository<ShareLink, UUID>,
        ShareLinkDeletionRepository {

    Optional<ShareLink> findByGenerationId(UUID generationId);
}
