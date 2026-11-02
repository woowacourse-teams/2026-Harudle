package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.harudle.auth.infrastructure.oauth.GoogleTokenRevocationClient.GoogleTokenRevocationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleTokenRevocationClientTest {

    @Test
    @DisplayName("구글 토큰을 요청 본문에 담아 연결을 해제한다")
    void revokeToken() {
        var builder = RestClient.builder().baseUrl("https://oauth2.googleapis.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("token=google-token"))
                .andRespond(withStatus(HttpStatus.OK));

        new GoogleTokenRevocationClient(builder.build()).revoke("google-token");

        server.verify();
    }

    @Test
    @DisplayName("구글 오류는 토큰을 제외하고 실패로 처리한다")
    void rejectProviderError() {
        var builder = RestClient.builder().baseUrl("https://oauth2.googleapis.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("google-token"));

        assertThatThrownBy(() -> new GoogleTokenRevocationClient(builder.build()).revoke("google-token"))
                .isInstanceOf(GoogleTokenRevocationException.class).hasNoCause();
    }
}
