package com.harudle.auth.infrastructure.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Objects;

public class AppleClientSecretGenerator {

    private static final String AUDIENCE = "https://appleid.apple.com";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final AppleClientSecretProperties properties;
    private final ECPrivateKey privateKey;
    private final Clock clock;

    public AppleClientSecretGenerator(AppleClientSecretProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "Apple client secret 설정은 필수입니다.");
        this.clock = Objects.requireNonNull(clock, "clock은 필수입니다.");
        this.privateKey = readPrivateKey(properties.privateKeyPath());
    }

    public String generate() {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.teamId())
                .subject(properties.clientId())
                .audience(AUDIENCE)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(TTL)))
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType.JWT)
                .keyID(properties.keyId())
                .build();
        SignedJWT jwt = new SignedJWT(header, claims);

        try {
            jwt.sign(new ECDSASigner(privateKey));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Apple client secret 서명에 실패했습니다.", exception);
        }
    }

    private static ECPrivateKey readPrivateKey(String privateKeyPath) {
        try {
            String pem = Files.readString(Path.of(privateKeyPath));
            String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(encoded);
            ECPrivateKey key = (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(decoded));
            if (!Curve.P_256.equals(Curve.forECParameterSpec(key.getParams()))) {
                throw new IllegalArgumentException("Apple 개인 키는 P-256이어야 합니다.");
            }
            return key;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | ClassCastException exception) {
            throw new IllegalStateException("Apple P-256 개인 키를 읽을 수 없습니다.", exception);
        }
    }

}
