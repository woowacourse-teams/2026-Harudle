package com.harudle.auth.application;

import com.harudle.auth.domain.User;
import com.harudle.auth.domain.exception.PublicProfileNotAssignedException;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.ProfileImageCleanupRepository;
import com.harudle.auth.infrastructure.UserRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileImageChangeService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final ProfileImageCleanupRepository cleanupRepository;
    private final Clock clock;

    public ProfileImageChangeService(
            UserRepository userRepository,
            OAuthAccountRepository oauthAccountRepository,
            ProfileImageCleanupRepository cleanupRepository,
            @Qualifier("authClock") Clock clock
    ) {
        this.userRepository = userRepository;
        this.oauthAccountRepository = oauthAccountRepository;
        this.cleanupRepository = cleanupRepository;
        this.clock = clock;
    }

    @Transactional
    public @Nullable String changeDefault(UUID userId, int imageCode) {
        User user = findForChange(userId);
        String previousObjectKey = user.getProfileImageObjectKey();

        user.changeDefaultProfileImage(imageCode, clock.instant());
        schedulePreviousImage(userId, previousObjectKey);

        return previousObjectKey;
    }

    @Transactional
    public @Nullable String changeUploaded(UUID userId, String objectKey) {
        User user = findForChange(userId);
        String previousObjectKey = user.getProfileImageObjectKey();

        user.changeUploadedProfileImage(objectKey, clock.instant());
        if (!Objects.equals(previousObjectKey, objectKey)) {
            schedulePreviousImage(userId, previousObjectKey);
        }

        return previousObjectKey;
    }

    @Transactional(readOnly = true)
    public void validateUser(UUID userId) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        requireEligibleUser(userRepository.findById(userId).orElseThrow(InvalidCurrentUserException::new));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean isUnused(UUID userId, String objectKey) {
        return userRepository.findByIdForUpdate(userId)
                .map(user -> !Objects.equals(user.getProfileImageObjectKey(), objectKey))
                .orElse(true);
    }

    private User findForChange(UUID userId) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(InvalidCurrentUserException::new);

        return requireEligibleUser(user);
    }

    private User requireEligibleUser(User user) {
        UUID userId = user.getId();
        if (user.isDeleted() || oauthAccountRepository.findByUser_Id(userId).isEmpty()) {
            throw new InvalidCurrentUserException();
        }

        if (user.getNickname() == null || user.getProfileImageCode() == null) {
            throw new PublicProfileNotAssignedException();
        }

        return user;
    }

    private void schedulePreviousImage(UUID userId, @Nullable String objectKey) {
        if (objectKey != null) {
            cleanupRepository.add(userId, objectKey, clock.instant());
        }
    }
}
