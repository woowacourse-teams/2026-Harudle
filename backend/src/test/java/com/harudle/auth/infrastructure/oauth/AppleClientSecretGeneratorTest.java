package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AppleClientSecretGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @TempDir
    private Path directory;

    @Test
    @DisplayName("Apple client secret을 ES256으로 서명하고 서비스 정보와 5분의 유효 기간을 담는다")
    void generatesSignedClientSecret() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).generate();
        var generator = new AppleClientSecretGenerator(writeKey(key), Clock.fixed(NOW, ZoneOffset.UTC));

        SignedJWT jwt = SignedJWT.parse(generator.generate());

        assertThat(jwt.verify(new ECDSAVerifier(key.toECPublicKey()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("test-key");
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("test-team");
        assertThat(claims.getSubject()).isEqualTo("com.harudle.web");
        assertThat(claims.getAudience()).containsExactly("https://appleid.apple.com");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("개인 키는 생성할 때 한 번 읽고 인증값은 호출 시각에 맞춰 새로 만든다")
    void reusesLoadedKeyAndRenewsClaims() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).generate();
        var properties = writeKey(key);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(60));
        var generator = new AppleClientSecretGenerator(properties, clock);
        Files.delete(Path.of(properties.privateKeyPath()));

        SignedJWT first = SignedJWT.parse(generator.generate());
        SignedJWT second = SignedJWT.parse(generator.generate());

        assertThat(first.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(second.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW.plusSeconds(60));
        assertThat(second.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plusSeconds(360));
        assertThat(second.verify(new ECDSAVerifier(key.toECPublicKey()))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing.p8", "invalid.p8"})
    @DisplayName("개인 키가 없거나 내용이 잘못되면 생성기를 만들 때 실패한다")
    void rejectsUnreadableKey(String fileName) throws Exception {
        Files.writeString(directory.resolve("invalid.p8"), "invalid-key");
        var properties = new AppleClientSecretProperties(
                "com.harudle.web", "test-team", "test-key", directory.resolve(fileName).toString()
        );

        assertThatThrownBy(() -> new AppleClientSecretGenerator(properties, Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Apple P-256 개인 키를 읽을 수 없습니다.");
    }

    private AppleClientSecretProperties writeKey(ECKey key) throws Exception {
        Path path = directory.resolve("test-only.p8");
        String encoded = Base64.getEncoder().encodeToString(key.toECPrivateKey().getEncoded());
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
        return new AppleClientSecretProperties("com.harudle.web", "test-team", "test-key", path.toString());
    }

}
