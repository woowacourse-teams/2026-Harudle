package com.harudle.auth.infrastructure;

import com.harudle.auth.application.RefreshTokenCleanupService;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupScheduler.class);

    private final RefreshTokenCleanupService cleanupService;
    private final Clock clock;

    public RefreshTokenCleanupScheduler(
            RefreshTokenCleanupService cleanupService,
            @Qualifier("authClock") Clock clock
    ) {
        this.cleanupService = cleanupService;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${app.auth.refresh-token.cleanup.cron:0 0 4 * * *}",
            zone = "${app.auth.refresh-token.cleanup.zone:Asia/Seoul}",
            scheduler = "refreshTokenCleanupTaskScheduler"
    )
    public void cleanupExpiredTokens() {
        Instant cutoff = clock.instant();
        long startedAt = System.nanoTime();
        try {
            int deletedCount = cleanupService.deleteExpiredTokens(cutoff);
            log.info("event=refresh_token_cleanup_run cutoff={} deletedCount={} durationMs={}",
                    cutoff, deletedCount, elapsedMillis(startedAt));
        } catch (RuntimeException exception) {
            log.error("event=refresh_token_cleanup_failed cutoff={} durationMs={} exceptionType={}",
                    cutoff, elapsedMillis(startedAt), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
