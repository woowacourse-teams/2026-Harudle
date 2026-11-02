package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.harudle.auth.infrastructure.oauth.KakaoUnlinkClient.KakaoUnlinkException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoUnlinkClientTest {

    @Test
    @DisplayName("관리자 키와 카카오 사용자 번호로 연결을 해제한다")
    void unlinkWithAdminKey() {
        var builder = RestClient.builder().baseUrl("https://kapi.kakao.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "KakaoAK admin-key"))
                .andExpect(content().string(containsString("target_id_type=user_id")))
                .andExpect(content().string(containsString("target_id=123")))
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

        new KakaoUnlinkClient("admin-key", builder.build()).unlink(123);

        server.verify();
    }

    @Test
    @DisplayName("다른 사용자의 연결 해제 응답은 성공으로 처리하지 않는다")
    void rejectMismatchedUser() {
        var builder = RestClient.builder().baseUrl("https://kapi.kakao.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
                .andRespond(withSuccess("{\"id\":456}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new KakaoUnlinkClient("admin-key", builder.build()).unlink(123))
                .isInstanceOf(KakaoUnlinkException.class);
    }

    @Test
    @DisplayName("카카오 오류는 민감한 응답을 제외하고 실패로 처리한다")
    void rejectProviderError() {
        var builder = RestClient.builder().baseUrl("https://kapi.kakao.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://kapi.kakao.com/v1/user/unlink"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("admin-key"));

        assertThatThrownBy(() -> new KakaoUnlinkClient("admin-key", builder.build()).unlink(123))
                .isInstanceOf(KakaoUnlinkException.class).hasNoCause();
    }
}
