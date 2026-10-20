package com.harudle.auth.application;

import com.harudle.auth.infrastructure.ProfileImageCleanupRepository;
import com.harudle.auth.infrastructure.ProfileImageCleanupRepository.PendingCleanup;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ProfileImageCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileImageCleanupService.class);
    private static final int BATCH_SIZE = 50;
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private final ProfileImageCleanupRepository cleanupRepository;
    private final ProfileImageChangeService imageChangeService;
    private final ObjectProvider<S3ProfileImageStorage> storageProvider;
    private final Clock clock;

    public ProfileImageCleanupService(
            ProfileImageCleanupRepository cleanupRepository,
            ProfileImageChangeService imageChangeService,
            ObjectProvider<S3ProfileImageStorage> storageProvider,
            @Qualifier("authClock") Clock clock
    ) {
        this.cleanupRepository = cleanupRepository;
        this.imageChangeService = imageChangeService;
        this.storageProvider = storageProvider;
        this.clock = clock;
    }

    public void clean(UUID userId, @Nullable String objectKey) {
        if (objectKey == null) {
            return;
        }

        try {
            cleanupRepository.add(userId, objectKey, clock.instant());
            cleanPending(new PendingCleanup(userId, objectKey));
        } catch (RuntimeException exception) {
            logFailure(objectKey, exception);
        }
    }

    public void defer(UUID userId, @Nullable String objectKey) {
        if (objectKey == null) {
            return;
        }

        try {
            cleanupRepository.add(userId, objectKey, clock.instant(), clock.instant().plus(RETRY_DELAY));
        } catch (RuntimeException exception) {
            logFailure(objectKey, exception);
        }
    }

    @Scheduled(fixedDelayString = "${harudle.profile.cleanup-interval:1m}",
            initialDelayString = "${harudle.profile.cleanup-interval:1m}")
    public void retryPending() {
        if (storageProvider.getIfAvailable() == null) {
            return;
        }

        cleanupRepository.findPending(clock.instant(), BATCH_SIZE).forEach(this::cleanPending);
    }

    private void cleanPending(PendingCleanup pending) {
        try {
            if (!imageChangeService.isUnused(pending.userId(), pending.objectKey())) {
                cleanupRepository.remove(pending.objectKey());
                return;
            }

            S3ProfileImageStorage storage = storageProvider.getIfAvailable();
            if (storage == null) {
                return;
            }

            storage.delete(pending.objectKey());
            cleanupRepository.remove(pending.objectKey());
        } catch (RuntimeException exception) {
            logFailure(pending.objectKey(), exception);
            cleanupRepository.defer(pending.objectKey(), clock.instant().plus(RETRY_DELAY));
        }
    }

    private void logFailure(String objectKey, RuntimeException exception) {
        LOGGER.warn("event=profile_image_cleanup_deferred objectKey={} exceptionType={}",
                objectKey, exception.getClass().getSimpleName());
    }
}
