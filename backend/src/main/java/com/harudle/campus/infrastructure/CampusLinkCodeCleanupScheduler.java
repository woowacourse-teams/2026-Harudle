package com.harudle.campus.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 만료된 지 하루가 지난 캠퍼스 연결 코드를 지운다. Refresh Token 정리와 같은 스케줄러를 쓴다. */
@Component
public class CampusLinkCodeCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CampusLinkCodeCleanupScheduler.class);
    private static final Duration RETENTION = Duration.ofDays(1);

    private final CampusLinkCodeRepository repository;
    private final Clock clock;

    public CampusLinkCodeCleanupScheduler(
            CampusLinkCodeRepository repository,
            @Qualifier("authClock") Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${app.auth.refresh-token.cleanup.cron:0 0 4 * * *}",
            zone = "${app.auth.refresh-token.cleanup.zone:Asia/Seoul}",
            scheduler = "refreshTokenCleanupTaskScheduler"
    )
    public void deleteExpiredCodes() {
        Instant cutoff = clock.instant().minus(RETENTION);
        try {
            int deletedCount = repository.deleteExpired(cutoff);
            log.info("event=campus_link_code_cleanup_run cutoff={} deletedCount={}", cutoff, deletedCount);
        } catch (RuntimeException exception) {
            log.error("event=campus_link_code_cleanup_failed cutoff={} exceptionType={}",
                    cutoff, exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
