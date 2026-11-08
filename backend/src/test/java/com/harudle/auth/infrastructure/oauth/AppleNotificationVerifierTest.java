package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.ObjectMapper;

class AppleNotificationVerifierTest {

    private final Instant now = Instant.now();
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void verifiesSignatureAndReadsRevocationEvent() throws Exception {
        var key = new RSAKeyGenerator(2048).generate();
        var verifier = verifier(key);
        String jwt = sign(key, "com.harudle.app", Map.of("type", "consent-revoked", "sub", "apple-subject",
                "event_time", now.minusSeconds(1).toEpochMilli()));

        var event = verifier.verify(jwt);

        assertThat(event.requiresWithdrawal()).isTrue();
        assertThat(event.subject()).isEqualTo("apple-subject");
        assertThat(event.occurredAt()).isEqualTo(Instant.ofEpochMilli(now.minusSeconds(1).toEpochMilli()));
        assertThat(event.toString()).doesNotContain("apple-subject");
    }

    @Test
    void rejectsWrongSignatureOrAudience() throws Exception {
        var key = new RSAKeyGenerator(2048).generate();
        var otherKey = new RSAKeyGenerator(2048).generate();
        var verifier = verifier(key);
        Map<String, Object> event = Map.of("type", "consent-revoked", "sub", "apple-subject", "event_time", now.getEpochSecond());

        assertThatThrownBy(() -> verifier.verify(sign(otherKey, "com.harudle.app", event)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> verifier.verify(sign(key, "different-app", event)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    @Test
    void emailChangeDoesNotRequestWithdrawal() throws Exception {
        var key = new RSAKeyGenerator(2048).generate();
        var event = verifier(key).verify(sign(key, "com.harudle.app", Map.of("type", "email-disabled")));

        assertThat(event.requiresWithdrawal()).isFalse();
    }

    @Test
    void readsAccountDeletionFromJsonStringWithSecondTimestamp() throws Exception {
        var key = new RSAKeyGenerator(2048).generate();
        String events = "{\"type\":\"account-deleted\",\"sub\":\"apple-subject\",\"event_time\":"
                + now.minusSeconds(1).getEpochSecond() + "}";

        var event = verifier(key).verify(sign(key, "web-client", events));

        assertThat(event.requiresWithdrawal()).isTrue();
        assertThat(event.occurredAt()).isEqualTo(Instant.ofEpochSecond(now.minusSeconds(1).getEpochSecond()));
    }

    private AppleNotificationVerifier verifier(RSAKey key) throws Exception {
        return new AppleNotificationVerifier("web-client", clock, new ObjectMapper(),
                NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build());
    }

    private String sign(RSAKey key, String audience, Object events) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("https://appleid.apple.com").audience(audience)
                .jwtID("notification-id").issueTime(Date.from(now)).claim("events", events).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
