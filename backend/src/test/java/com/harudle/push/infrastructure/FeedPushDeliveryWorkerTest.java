package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.ErrorCode;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class FeedPushDeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    private JdbcPushDeliveryQueue queue;
    private FcmFeedPushSender sender;
    private FeedPushDeliveryWorker worker;
    private ThreadPoolTaskScheduler scheduler;
    private JdbcPushDeliveryQueue.Claim claim;
    private JdbcPushDeliveryQueue.Target target;

    @BeforeEach
    void setUp() {
        queue = mock(JdbcPushDeliveryQueue.class);
        sender = mock(FcmFeedPushSender.class);
        scheduler = mock(ThreadPoolTaskScheduler.class);
        worker = new FeedPushDeliveryWorker(queue, sender, Clock.fixed(NOW, ZoneOffset.UTC), scheduler);
        claim = new JdbcPushDeliveryQueue.Claim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), NOW.plusSeconds(300), 1, NOW.minusSeconds(3600));
        target = new JdbcPushDeliveryQueue.Target("F1", 0);
        when(queue.claim(NOW)).thenReturn(Optional.of(claim), Optional.empty());
        when(queue.target(claim, NOW)).thenReturn(Optional.of(target));
    }

    @Test
    @DisplayName("유효한 등록은 현재 FID로 FCM에 보내고 수락되면 SENT로 기록한다")
    void sendsAndRecordsAcceptance() throws Exception {
        worker.runBatch();

        verify(queue).expire(NOW);
        verify(sender).send("F1", claim.eventId(), claim.feedId());
        verify(queue).sent(claim, NOW);
    }

    @Test
    @DisplayName("새 발송 작업 신호를 받으면 워커를 즉시 실행하도록 예약한다")
    void wakesAfterJobsQueued() {
        worker.onJobsQueued(new JpaFeedPushOutbox.JobsQueued());

        verify(scheduler).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("즉시 실행 예약에 실패해도 피드 게시 결과를 바꾸지 않는다")
    void keepsPublishResultWhenWakeupFails() {
        doThrow(new IllegalStateException("scheduler stopped"))
                .when(scheduler).execute(any(Runnable.class));

        worker.onJobsQueued(new JpaFeedPushOutbox.JobsQueued());

        verify(scheduler).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("발송 작업이 100건을 넘으면 다음 주기를 기다리지 않고 남은 작업을 처리한다")
    void drainsMoreThanOneBatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(queue.claim(NOW)).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 101) {
                return Optional.empty();
            }
            return Optional.of(new JdbcPushDeliveryQueue.Claim(UUID.randomUUID(), claim.eventId(),
                    claim.feedId(), claim.registrationId(), UUID.randomUUID(),
                    claim.lockedUntil(), 1, claim.publishedAt()));
        });
        when(queue.target(any(), eq(NOW))).thenReturn(Optional.of(target));

        worker.drain();

        verify(sender, times(101)).send("F1", claim.eventId(), claim.feedId());
        verify(queue, times(102)).claim(NOW);
    }

    @Test
    @DisplayName("전송 직전에 수신 대상이 사라지면 FCM을 호출하지 않고 작업을 취소한다")
    void cancelsMissingTarget() throws Exception {
        when(queue.target(claim, NOW)).thenReturn(Optional.empty());

        worker.runBatch();

        verify(queue).cancel(claim, NOW);
        verify(sender, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("일시 오류는 10초 이후 다른 시각으로 재시도하고 같은 eventId를 사용한다")
    void retriesTemporaryError() throws Exception {
        FirebaseMessagingException exception = error(MessagingErrorCode.UNAVAILABLE, ErrorCode.UNAVAILABLE);
        when(sender.send("F1", claim.eventId(), claim.feedId()))
                .thenThrow(exception);

        worker.runBatch();

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(queue).retry(eq(claim), next.capture(), eq("UNAVAILABLE"), eq(NOW));
        assertThat(next.getValue()).isBetween(NOW.plusSeconds(10), NOW.plusSeconds(20));
        verify(sender).send("F1", claim.eventId(), claim.feedId());
    }

    @Test
    @DisplayName("전송 중 예외로 FCM 결과를 모르면 작업을 다시 예약한다")
    void retriesUnknownSendResult() throws Exception {
        when(sender.send("F1", claim.eventId(), claim.feedId()))
                .thenThrow(new IllegalStateException("전송 결과를 확인하지 못함"));

        worker.runBatch();

        verify(queue).retry(eq(claim), any(Instant.class), eq("SEND_EXCEPTION"), eq(NOW));
        verify(queue, never()).sent(claim, NOW);
    }

    @Test
    @DisplayName("FCM이 Retry-After를 주면 그 시각보다 먼저 재시도하지 않는다")
    void respectsRetryAfter() throws Exception {
        FirebaseMessagingException exception = error(MessagingErrorCode.UNAVAILABLE, ErrorCode.UNAVAILABLE);
        IncomingHttpResponse response = mock(IncomingHttpResponse.class);
        when(response.getHeaders()).thenReturn(Map.of("retry-after", "120"));
        when(exception.getHttpResponse()).thenReturn(response);
        when(sender.send("F1", claim.eventId(), claim.feedId())).thenThrow(exception);

        worker.runBatch();

        verify(queue).retry(claim, NOW.plusSeconds(120), "UNAVAILABLE", NOW);
    }

    @Test
    @DisplayName("FCM 전송량 제한은 Retry-After가 없어도 최소 60초 뒤에 재시도한다")
    void waitsOnQuotaError() throws Exception {
        FirebaseMessagingException exception = error(MessagingErrorCode.QUOTA_EXCEEDED, ErrorCode.RESOURCE_EXHAUSTED);
        when(sender.send("F1", claim.eventId(), claim.feedId()))
                .thenThrow(exception);

        worker.runBatch();

        verify(queue).retry(claim, NOW.plusSeconds(60), "QUOTA_EXCEEDED", NOW);
    }

    @Test
    @DisplayName("다음 재시도가 게시 후 24시간을 넘으면 다시 예약하지 않고 종료한다")
    void stopsRetryAtDeadline() throws Exception {
        JdbcPushDeliveryQueue.Claim nearDeadline = new JdbcPushDeliveryQueue.Claim(claim.id(),
                claim.eventId(), claim.feedId(), claim.registrationId(), claim.lockToken(),
                claim.lockedUntil(), 1, NOW.minus(Duration.ofHours(24)).plusSeconds(5));
        FirebaseMessagingException exception = error(MessagingErrorCode.UNAVAILABLE, ErrorCode.UNAVAILABLE);
        when(queue.claim(NOW)).thenReturn(Optional.of(nearDeadline), Optional.empty());
        when(queue.target(nearDeadline, NOW)).thenReturn(Optional.of(target));
        when(sender.send("F1", claim.eventId(), claim.feedId())).thenThrow(exception);

        worker.runBatch();

        verify(queue).fail(nearDeadline, "RETRY_DEADLINE", NOW);
        verify(queue, never()).retry(eq(nearDeadline), any(), any(), any());
    }

    @Test
    @DisplayName("다시 보내도 해결되지 않는 FCM 오류는 FAILED로 끝낸다")
    void failsPermanentError() throws Exception {
        FirebaseMessagingException exception = error(MessagingErrorCode.INVALID_ARGUMENT, ErrorCode.INVALID_ARGUMENT);
        when(sender.send("F1", claim.eventId(), claim.feedId()))
                .thenThrow(exception);

        worker.runBatch();

        verify(queue).fail(claim, "INVALID_ARGUMENT", NOW);
        verify(queue, never()).retry(eq(claim), any(), any(), any());
    }

    @Test
    @DisplayName("무효 FID가 그대로면 해당 등록과 미발송 작업을 해제한다")
    void revokesUnregisteredFid() throws Exception {
        FirebaseMessagingException exception = error(MessagingErrorCode.UNREGISTERED, ErrorCode.NOT_FOUND);
        when(sender.send("F1", claim.eventId(), claim.feedId()))
                .thenThrow(exception);
        when(queue.revokeUnregistered(claim, target, NOW)).thenReturn(true);

        worker.runBatch();

        verify(queue).revokeUnregistered(claim, target, NOW);
        verify(queue, never()).retry(eq(claim), any(), any(), any());
    }

    @Test
    @DisplayName("FCM 전송 중 FID가 바뀌면 새 등록을 유지하고 같은 작업을 재시도한다")
    void retriesWhenFidChanged() throws Exception {
        FirebaseMessagingException exception = error(MessagingErrorCode.UNREGISTERED, ErrorCode.NOT_FOUND);
        when(sender.send("F1", claim.eventId(), claim.feedId()))
                .thenThrow(exception);
        when(queue.target(claim, NOW)).thenReturn(Optional.of(target),
                Optional.of(new JdbcPushDeliveryQueue.Target("F2", 1)));

        worker.runBatch();

        verify(queue).revokeUnregistered(claim, target, NOW);
        verify(queue).retry(eq(claim), any(Instant.class), eq("UNREGISTERED_OLD_FID"), eq(NOW));
        verify(queue, never()).cancel(claim, NOW);
    }

    @Test
    @DisplayName("24시간이 지났거나 점유 시간이 끝났으면 새 FCM 요청을 시작하지 않는다")
    void skipsExpiredClaims() throws Exception {
        JdbcPushDeliveryQueue.Claim old = new JdbcPushDeliveryQueue.Claim(claim.id(), claim.eventId(),
                claim.feedId(), claim.registrationId(), claim.lockToken(), claim.lockedUntil(), 1,
                NOW.minus(Duration.ofHours(24)));
        when(queue.claim(NOW)).thenReturn(Optional.of(old), Optional.empty());
        worker.runBatch();
        verify(queue).fail(old, "RETRY_DEADLINE", NOW);

        JdbcPushDeliveryQueue.Claim leaseExpired = new JdbcPushDeliveryQueue.Claim(claim.id(), claim.eventId(),
                claim.feedId(), claim.registrationId(), claim.lockToken(), NOW, 1, claim.publishedAt());
        when(queue.claim(NOW)).thenReturn(Optional.of(leaseExpired), Optional.empty());
        worker.runBatch();
        verify(sender, never()).send(any(), any(), any());
        verify(queue, never()).cancel(leaseExpired, NOW);
    }

    private FirebaseMessagingException error(MessagingErrorCode messaging, ErrorCode general) {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(messaging);
        when(exception.getErrorCode()).thenReturn(general);
        return exception;
    }
}
