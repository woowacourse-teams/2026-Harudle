package com.harudle.push.infrastructure;

import com.google.firebase.ErrorCode;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(prefix = "harudle.push.fcm", name = "enabled", havingValue = "true")
class FeedPushDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(FeedPushDeliveryWorker.class);
    private static final Duration DEADLINE = Duration.ofHours(24);
    private static final int BATCH_SIZE = 100;
    static final int WORKER_COUNT = 4;

    private final JdbcPushDeliveryQueue queue;
    private final FcmFeedPushSender sender;
    private final Clock clock;
    private final ThreadPoolTaskScheduler scheduler;
    private final AtomicInteger activeWorkers = new AtomicInteger();

    FeedPushDeliveryWorker(JdbcPushDeliveryQueue queue, FcmFeedPushSender sender,
                           @Qualifier("serviceClock") Clock clock,
                           @Qualifier("pushTaskScheduler") ThreadPoolTaskScheduler scheduler) {
        this.queue = queue;
        this.sender = sender;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onJobsQueued(JpaFeedPushOutbox.JobsQueued event) {
        startDrains();
    }

    @Scheduled(fixedDelayString = "${harudle.push.worker.interval:30s}",
            initialDelayString = "${harudle.push.worker.interval:30s}", scheduler = "pushTaskScheduler")
    void checkPending() {
        startDrains();
    }

    private void startDrains() {
        for (int i = 0; i < WORKER_COUNT; i++) {
            if (activeWorkers.getAndUpdate(count -> Math.min(WORKER_COUNT, count + 1)) == WORKER_COUNT) return;
            try {
                scheduler.execute(this::drainAndRelease);
            } catch (RuntimeException exception) {
                activeWorkers.decrementAndGet();
                log.warn("푸시 워커를 실행하지 못했습니다. 다음 주기 확인에서 다시 처리합니다.", exception);
                return;
            }
        }
    }

    private void drainAndRelease() {
        try {
            drain();
        } catch (RuntimeException exception) {
            log.warn("푸시 워커가 중단됐습니다. 다음 주기 확인에서 다시 처리합니다.", exception);
        } finally {
            activeWorkers.decrementAndGet();
        }
    }

    void drain() {
        boolean fullBatch;
        do {
            fullBatch = runBatch();
        } while (fullBatch);
    }

    boolean runBatch() {
        queue.expire(clock.instant());
        for (int processed = 0; processed < BATCH_SIZE; processed++) {
            Optional<JdbcPushDeliveryQueue.Claim> claim = queue.claim(clock.instant());
            if (claim.isEmpty()) {
                return false;
            }
            deliver(claim.orElseThrow());
        }
        return true;
    }

    private void deliver(JdbcPushDeliveryQueue.Claim claim) {
        Instant now = clock.instant();
        if (!now.isBefore(claim.publishedAt().plus(DEADLINE))) {
            queue.fail(claim, "RETRY_DEADLINE", now);
            return;
        }
        if (!now.isBefore(claim.lockedUntil())) {
            return;
        }
        sendToCurrentTarget(claim, now);
    }

    private void sendToCurrentTarget(JdbcPushDeliveryQueue.Claim claim, Instant now) {
        Optional<JdbcPushDeliveryQueue.Target> target = queue.target(claim, now);
        if (target.isEmpty()) {
            queue.cancel(claim, now);
            return;
        }
        send(claim, target.orElseThrow());
    }

    private void send(JdbcPushDeliveryQueue.Claim claim, JdbcPushDeliveryQueue.Target target) {
        try {
            sender.send(target.fid(), claim.eventId(), claim.feedId());
        } catch (FirebaseMessagingException exception) {
            handleError(claim, target, exception);
            return;
        } catch (RuntimeException exception) {
            log.warn("피드 푸시 전송 중 예외가 발생했습니다. jobId={} exceptionType={}",
                    claim.id(), exception.getClass().getSimpleName());
            retry(claim, "SEND_EXCEPTION", null);
            return;
        }
        queue.sent(claim, clock.instant());
    }

    private void handleError(JdbcPushDeliveryQueue.Claim claim, JdbcPushDeliveryQueue.Target target,
                             FirebaseMessagingException exception) {
        MessagingErrorCode code = exception.getMessagingErrorCode();
        if (code == MessagingErrorCode.UNREGISTERED) {
            handleUnregistered(claim, target);
            return;
        }
        String name = errorName(exception);
        if (retryable(exception)) {
            retry(claim, name, exception);
            return;
        }
        queue.fail(claim, name, clock.instant());
        log.warn("피드 푸시 발송을 종료했습니다. jobId={} errorCode={}", claim.id(), name);
    }

    private void handleUnregistered(JdbcPushDeliveryQueue.Claim claim, JdbcPushDeliveryQueue.Target target) {
        Instant now = clock.instant();
        if (queue.revokeUnregistered(claim, target, now)) {
            return;
        }
        if (queue.target(claim, now).isEmpty()) {
            queue.cancel(claim, now);
            return;
        }
        retry(claim, "UNREGISTERED_OLD_FID", null);
    }

    private void retry(JdbcPushDeliveryQueue.Claim claim, String code, FirebaseMessagingException exception) {
        Instant now = clock.instant();
        Instant next = nextRetry(claim, exception, now);
        if (!next.isBefore(claim.publishedAt().plus(DEADLINE))) {
            queue.fail(claim, "RETRY_DEADLINE", now);
            return;
        }
        queue.retry(claim, next, code, now);
    }

    private Instant nextRetry(JdbcPushDeliveryQueue.Claim claim, FirebaseMessagingException exception, Instant now) {
        Instant next = now.plusSeconds(backoffSeconds(claim.attemptCount()));
        if (exception == null) {
            return next;
        }
        next = later(next, retryAfter(exception, now));
        if (exception.getMessagingErrorCode() == MessagingErrorCode.QUOTA_EXCEEDED) {
            next = later(next, now.plusSeconds(60));
        }
        return next;
    }

    private String errorName(FirebaseMessagingException exception) {
        MessagingErrorCode code = exception.getMessagingErrorCode();
        if (code != null) {
            return code.name();
        }
        return exception.getErrorCode().name();
    }

    private boolean retryable(FirebaseMessagingException exception) {
        MessagingErrorCode code = exception.getMessagingErrorCode();
        if (code != null) {
            return code == MessagingErrorCode.UNAVAILABLE || code == MessagingErrorCode.INTERNAL
                    || code == MessagingErrorCode.QUOTA_EXCEEDED;
        }
        ErrorCode general = exception.getErrorCode();
        return general == ErrorCode.UNAVAILABLE || general == ErrorCode.INTERNAL
                || general == ErrorCode.DEADLINE_EXCEEDED || general == ErrorCode.RESOURCE_EXHAUSTED
                || general == ErrorCode.UNKNOWN;
    }

    private long backoffSeconds(int attemptCount) {
        long ceiling = Math.min(3600, 20L << Math.min(Math.max(0, attemptCount - 1), 8));
        return ThreadLocalRandom.current().nextLong(Math.max(10, ceiling / 2), ceiling + 1);
    }

    private Instant retryAfter(FirebaseMessagingException exception, Instant now) {
        IncomingHttpResponse response = exception.getHttpResponse();
        if (response == null || response.getHeaders() == null) {
            return now;
        }
        return response.getHeaders().entrySet().stream()
                .filter(entry -> "Retry-After".equalsIgnoreCase(entry.getKey()))
                .findFirst().map(entry -> parseRetryAfter(entry.getValue(), now)).orElse(now);
    }

    private Instant parseRetryAfter(Object value, Instant now) {
        String header = headerText(value);
        try {
            return now.plusSeconds(Math.max(0, Long.parseLong(header)));
        } catch (NumberFormatException exception) {
            try {
                return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignored) {
                return now;
            }
        }
    }

    private String headerText(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.getFirst());
        }
        return String.valueOf(value);
    }

    private Instant later(Instant first, Instant second) {
        if (first.isAfter(second)) {
            return first;
        }
        return second;
    }
}
