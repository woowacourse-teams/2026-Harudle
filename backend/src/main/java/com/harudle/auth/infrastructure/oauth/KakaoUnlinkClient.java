package com.harudle.auth.infrastructure.oauth;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class KakaoUnlinkClient {

    private final String adminKey;
    private final RestClient client;

    @Autowired
    public KakaoUnlinkClient(@Value("${app.auth.kakao.admin-key:}") String adminKey) {
        this(adminKey, createClient());
    }

    KakaoUnlinkClient(String adminKey, RestClient client) {
        this.adminKey = adminKey;
        this.client = client;
    }

    public void unlink(long kakaoUserId) {
        Assert.isTrue(kakaoUserId > 0, "카카오 사용자 ID는 양수여야 합니다.");
        Assert.hasText(adminKey, "카카오 관리자 키가 필요합니다.");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("target_id_type", "user_id");
        form.add("target_id", Long.toString(kakaoUserId));

        try {
            var response = client.post().uri("/v1/user/unlink")
                    .header("Authorization", "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().toEntity(UnlinkResponse.class);
            var body = response.getBody();
            if (response.getStatusCode().value() != 200 || body == null || body.id() == null || body.id() != kakaoUserId) {
                throw new KakaoUnlinkException();
            }
        } catch (RestClientResponseException exception) {
            if (alreadyUnlinked(exception)) {
                return;
            }
            throw new KakaoUnlinkException();
        } catch (RestClientException exception) {
            throw new KakaoUnlinkException();
        }
    }

    private boolean alreadyUnlinked(RestClientResponseException exception) {
        try {
            var error = exception.getResponseBodyAs(KakaoError.class);
            return exception.getStatusCode().value() == 400 && error != null && Integer.valueOf(-101).equals(error.code());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static RestClient createClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl("https://kapi.kakao.com").requestFactory(factory).build();
    }

    record UnlinkResponse(Long id) {}

    record KakaoError(Integer code) {}

    public static final class KakaoUnlinkException extends RuntimeException {}
}
