package com.harudle.auth.infrastructure.oauth;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "app.auth.apple.enabled", havingValue = "true")
public class AppleTokenRevocationClient {

    private static final Logger log = LoggerFactory.getLogger(AppleTokenRevocationClient.class);
    private final AppleClientSecretGenerator secretGenerator;
    private final RestClient client;

    @Autowired
    public AppleTokenRevocationClient(AppleClientSecretGenerator secretGenerator) {
        this(secretGenerator, createAppleClient());
    }

    AppleTokenRevocationClient(AppleClientSecretGenerator secretGenerator, RestClient client) {
        this.secretGenerator = Objects.requireNonNull(secretGenerator);
        this.client = Objects.requireNonNull(client);
    }

    /** 토큰은 탈퇴할 회원에게 발급된 Apple 접근 토큰 또는 갱신 토큰이어야 한다. */
    public Result revoke(String clientId, @Nullable String token) {
        if (!StringUtils.hasText(token)) {
            return Result.TOKEN_UNAVAILABLE;
        }
        Assert.hasText(clientId, "Apple clientId는 필수입니다.");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", secretGenerator.generate(clientId));
        form.add("token", token);
        try {
            var response = client.post().uri("/auth/revoke")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().toBodilessEntity();
            if (response.getStatusCode().value() != 200) {
                throw new AppleTokenRevocationException();
            }
            return Result.REVOKED;
        } catch (RestClientException exception) {
            log.warn("Apple 토큰 해제 요청 실패: type={}", exception.getClass().getSimpleName());
            throw new AppleTokenRevocationException();
        }
    }

    private static RestClient createAppleClient() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl("https://appleid.apple.com").requestFactory(requestFactory).build();
    }

    public enum Result {
        REVOKED,
        TOKEN_UNAVAILABLE
    }

    public static final class AppleTokenRevocationException extends RuntimeException {}
}
