package com.harudle.auth.infrastructure.oauth;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GoogleTokenRevocationClient {

    private final RestClient client;

    @Autowired
    public GoogleTokenRevocationClient() {
        this(createClient());
    }

    GoogleTokenRevocationClient(RestClient client) {
        this.client = client;
    }

    public void revoke(String token) {
        Assert.hasText(token, "구글 토큰이 필요합니다.");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("token", token);
        try {
            var response = client.post().uri("/revoke")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().toBodilessEntity();
            if (response.getStatusCode().value() != 200) {
                throw new GoogleTokenRevocationException();
            }
        } catch (RestClientException exception) {
            throw new GoogleTokenRevocationException();
        }
    }

    private static RestClient createClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl("https://oauth2.googleapis.com").requestFactory(factory).build();
    }

    public static final class GoogleTokenRevocationException extends RuntimeException {}
}
