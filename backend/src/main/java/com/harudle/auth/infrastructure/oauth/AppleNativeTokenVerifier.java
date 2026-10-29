package com.harudle.auth.infrastructure.oauth;

import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "app.auth.apple.enabled", havingValue = "true")
public class AppleNativeTokenVerifier {
    private static final String ISSUER = "https://appleid.apple.com";
    private static final String IOS_CLIENT_ID = "com.harudle.app";
    private final AppleClientSecretGenerator secretGenerator;
    private final String clientId;
    private final JwtDecoder decoder;
    private final RestClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public AppleNativeTokenVerifier(AppleClientSecretGenerator secretGenerator) {
        this(secretGenerator, IOS_CLIENT_ID, NimbusJwtDecoder.withJwkSetUri(ISSUER + "/auth/keys").build(),
                RestClient.create(ISSUER));
    }

    AppleNativeTokenVerifier(AppleClientSecretGenerator secretGenerator, String clientId,
            JwtDecoder decoder, RestClient client) {
        Assert.hasText(clientId, "Apple 네이티브 clientId는 필수입니다.");
        this.secretGenerator = secretGenerator;
        this.clientId = clientId;
        this.decoder = decoder;
        this.client = client;
    }

    public Jwt verify(String code, String identityToken, String nonce) {
        Jwt nativeToken = decoder.decode(identityToken);
        validate(nativeToken);
        if (!Objects.equals(nonce, nativeToken.getClaimAsString("nonce"))) {
            throw new IllegalArgumentException("Apple nonce가 일치하지 않습니다.");
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", secretGenerator.generate(clientId));
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        TokenResponse response = client.post().uri("/auth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(TokenResponse.class);
        if (response == null || response.id_token() == null) {
            throw new IllegalArgumentException("Apple 인증 결과가 없습니다.");
        }
        Jwt exchanged = decoder.decode(response.id_token());
        validate(exchanged);
        if (!nativeToken.getSubject().equals(exchanged.getSubject())) {
            throw new IllegalArgumentException("Apple 사용자 정보가 일치하지 않습니다.");
        }
        return exchanged;
    }

    private void validate(Jwt jwt) {
        if (!ISSUER.equals(jwt.getClaimAsString("iss"))
                || !jwt.getAudience().contains(clientId)
                || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now())
                || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("Apple 인증 정보가 유효하지 않습니다.");
        }
    }

    record TokenResponse(String id_token) {}
}
