package com.harudle.push.infrastructure;

import com.google.firebase.ErrorCode;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class FcmRetryPolicy {

    String errorName(FirebaseMessagingException exception) {
        MessagingErrorCode code = exception.getMessagingErrorCode();
        if (code != null) {
            return code.name();
        }
        return exception.getErrorCode().name();
    }

    boolean retryable(FirebaseMessagingException exception) {
        MessagingErrorCode code = exception.getMessagingErrorCode();
        if (code != null) {
            return code == MessagingErrorCode.UNAVAILABLE || code == MessagingErrorCode.INTERNAL
                    || code == MessagingErrorCode.QUOTA_EXCEEDED;
        }
        return retryableGeneralError(exception.getErrorCode());
    }

    private boolean retryableGeneralError(ErrorCode code) {
        return code == ErrorCode.UNAVAILABLE || code == ErrorCode.INTERNAL
                || code == ErrorCode.DEADLINE_EXCEEDED || code == ErrorCode.RESOURCE_EXHAUSTED
                || code == ErrorCode.UNKNOWN;
    }

    Instant nextRetry(int attemptCount, FirebaseMessagingException exception, Instant now) {
        Instant next = now.plusSeconds(backoffSeconds(attemptCount));
        if (exception == null) {
            return next;
        }
        next = later(next, retryAfter(exception, now));
        if (exception.getMessagingErrorCode() == MessagingErrorCode.QUOTA_EXCEEDED) {
            next = later(next, now.plusSeconds(60));
        }
        return next;
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
            return parseRetryDate(header, now);
        }
    }

    private Instant parseRetryDate(String header, Instant now) {
        try {
            return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            return now;
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
