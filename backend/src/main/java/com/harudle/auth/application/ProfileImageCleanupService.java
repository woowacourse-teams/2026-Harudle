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
import org.springframework.stereotype.Service;

@Service
public class ProfileImageCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProfileImageCleanupService.class);
    private static final int BATCH_SIZE = 50;
    private static final Duration UPLOAD_CLEANUP_DELAY = Duration.ofMinutes(1);
    private static final Duration CLEANUP_TIME_LIMIT = Duration.ofSeconds(5);

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
        try {
            if (objectKey != null) {
                cleanupRepository.add(userId, objectKey, clock.instant());
            }
            cleanPendingForUser(userId);
        } catch (RuntimeException exception) {
            logFailure(objectKey, exception);
        }
    }

    public void defer(UUID userId, @Nullable String objectKey) {
        if (objectKey == null) {
            return;
        }

        try {
            cleanupRepository.add(userId, objectKey, clock.instant(), clock.instant().plus(UPLOAD_CLEANUP_DELAY));
        } catch (RuntimeException exception) {
            logFailure(objectKey, exception);
        }
    }

    private void cleanPendingForUser(UUID userId) {
        if (storageProvider.getIfAvailable() == null) {
            return;
        }

        long startedAt = System.nanoTime();
        for (PendingCleanup pending : cleanupRepository.findPending(userId, clock.instant(), BATCH_SIZE)) {
            if (System.nanoTime() - startedAt >= CLEANUP_TIME_LIMIT.toNanos()) {
                break;
            }
            cleanPending(pending);
        }
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
        }
    }

    private void logFailure(@Nullable String objectKey, RuntimeException exception) {
        LOGGER.warn("event=profile_image_cleanup_deferred objectKey={} exceptionType={}",
                objectKey, exception.getClass().getSimpleName());
    }
}
