package com.harudle.auth.infrastructure.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Component
public class AppleNotificationVerifier {

    private static final String ISSUER = "https://appleid.apple.com";
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private final String webClientId;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final JwtDecoder decoder;

    @Autowired
    public AppleNotificationVerifier(@Value("${app.auth.apple.client-id:}") String webClientId,
            @Qualifier("authClock") Clock clock, ObjectMapper mapper) {
        this(webClientId, clock, mapper, AppleNativeTokenVerifier.createAppleDecoder(ISSUER + "/auth/keys"));
    }

    AppleNotificationVerifier(String webClientId, Clock clock, ObjectMapper mapper, JwtDecoder decoder) {
        this.webClientId = webClientId;
        this.clock = clock;
        this.mapper = mapper;
        this.decoder = decoder;
    }

    public Event verify(String payload) {
        Jwt jwt;
        try {
            jwt = decoder.decode(payload);
        } catch (BadJwtException exception) {
            throw new IllegalArgumentException("애플 알림을 확인할 수 없습니다.");
        } catch (JwtException exception) {
            throw new AppleNotificationVerificationUnavailableException();
        }
        try {
            if (!ISSUER.equals(jwt.getClaimAsString("iss")) || !StringUtils.hasText(jwt.getId())
                    || jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(clock.instant().plus(CLOCK_SKEW))
                    || !(jwt.getAudience().contains(AppleNativeTokenVerifier.IOS_CLIENT_ID)
                    || (StringUtils.hasText(webClientId) && jwt.getAudience().contains(webClientId)))) {
                throw new IllegalArgumentException();
            }
            Object value = jwt.getClaim("events");
            EventPayload event = value instanceof String json
                    ? mapper.readValue(json, EventPayload.class) : mapper.convertValue(value, EventPayload.class);
            if (event == null || !StringUtils.hasText(event.type())) {
                throw new IllegalArgumentException();
            }
            if (!"consent-revoked".equals(event.type()) && !"account-deleted".equals(event.type())) {
                return new Event(event.type(), null, null);
            }
            if (!StringUtils.hasText(event.sub()) || event.event_time() == null || event.event_time() <= 0) {
                throw new IllegalArgumentException();
            }
            long rawTime = event.event_time();
            Instant eventTime = rawTime >= 100_000_000_000L ? Instant.ofEpochMilli(rawTime) : Instant.ofEpochSecond(rawTime);
            if (eventTime.isAfter(clock.instant().plus(CLOCK_SKEW))
                    || eventTime.isAfter(jwt.getIssuedAt().plus(CLOCK_SKEW))) {
                throw new IllegalArgumentException();
            }
            return new Event(event.type(), event.sub(), eventTime);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("애플 알림을 확인할 수 없습니다.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventPayload(String type, String sub, Long event_time) {}

    public record Event(String type, @Nullable String subject, @Nullable Instant occurredAt) {
        public boolean requiresWithdrawal() {
            return "consent-revoked".equals(type) || "account-deleted".equals(type);
        }

        @Override
        public String toString() {
            return "Event[type=" + type + "]";
        }
    }

    public static final class AppleNotificationVerificationUnavailableException extends RuntimeException {}
}
