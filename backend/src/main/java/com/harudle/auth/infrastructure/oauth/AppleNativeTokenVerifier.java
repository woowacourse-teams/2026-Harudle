package com.harudle.auth.infrastructure.oauth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "app.auth.apple.enabled", havingValue = "true")
public class AppleNativeTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(AppleNativeTokenVerifier.class);
    private static final String ISSUER = "https://appleid.apple.com";
    private static final String IOS_CLIENT_ID = "com.harudle.app";
    private final AppleClientSecretGenerator secretGenerator;
    private final String clientId;
    private final JwtDecoder decoder;
    private final RestClient client;

    @org.springframework.beans.factory.annotation.Autowired
    public AppleNativeTokenVerifier(AppleClientSecretGenerator secretGenerator) {
        this(secretGenerator, IOS_CLIENT_ID, NimbusJwtDecoder.withJwkSetUri(ISSUER + "/auth/keys").build(),
                createAppleClient());
    }

    private static RestClient createAppleClient() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(ISSUER).requestFactory(requestFactory).build();
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
        Jwt nativeToken = decode(identityToken);
        validate(nativeToken);
        if (!Objects.equals(nonce, nativeToken.getClaimAsString("nonce"))) {
            throw new IllegalArgumentException("Apple nonce가 일치하지 않습니다.");
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", secretGenerator.generate(clientId));
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        TokenResponse response;
        try {
            response = client.post().uri("/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(TokenResponse.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 400 && isInvalidGrant(exception)) {
                throw new InvalidAuthorizationCodeException();
            }
            log.warn("Apple 토큰 요청 실패: type={}, status={}",
                    exception.getClass().getSimpleName(), exception.getStatusCode().value());
            throw new AppleProviderException();
        } catch (RestClientException exception) {
            log.warn("Apple 토큰 요청 실패: type={}", exception.getClass().getSimpleName());
            throw new AppleProviderException();
        }
        if (response == null || !StringUtils.hasText(response.id_token())) {
            log.warn("Apple 토큰 응답에 ID 토큰이 없습니다");
            throw new AppleProviderException();
        }
        Jwt exchanged = decode(response.id_token());
        validate(exchanged);
        if (!nativeToken.getSubject().equals(exchanged.getSubject())) {
            throw new IllegalArgumentException("Apple 사용자 정보가 일치하지 않습니다.");
        }
        return exchanged;
    }

    private boolean isInvalidGrant(RestClientResponseException exception) {
        try {
            Map<?, ?> body = exception.getResponseBodyAs(Map.class);
            return body != null && "invalid_grant".equals(body.get("error"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Jwt decode(String token) {
        try {
            return decoder.decode(token);
        } catch (BadJwtException exception) {
            throw new IllegalArgumentException("Apple ID 토큰이 유효하지 않습니다.");
        } catch (JwtException exception) {
            log.warn("Apple ID 토큰 검증 실패: type={}", exception.getClass().getSimpleName());
            throw new AppleProviderException();
        }
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

    public static final class InvalidAuthorizationCodeException extends IllegalArgumentException {}

    public static final class AppleProviderException extends RuntimeException {}
}
