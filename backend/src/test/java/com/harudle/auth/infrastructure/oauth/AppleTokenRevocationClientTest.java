package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.harudle.auth.infrastructure.oauth.AppleTokenRevocationClient.AppleTokenRevocationException;
import com.harudle.auth.infrastructure.oauth.AppleTokenRevocationClient.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AppleTokenRevocationClientTest {

    @Test
    @DisplayName("해제할 토큰이 없으면 애플에 요청하지 않고 토큰이 없음을 반환한다")
    void reportMissingTokenWithoutCallingApple() {
        var generator = mock(AppleClientSecretGenerator.class);
        var client = mock(RestClient.class);
        var revoker = new AppleTokenRevocationClient(generator, client);

        assertThat(revoker.revoke("apple-client", null)).isEqualTo(Result.TOKEN_UNAVAILABLE);
        assertThat(revoker.revoke("apple-client", " ")).isEqualTo(Result.TOKEN_UNAVAILABLE);
        verifyNoInteractions(generator, client);
    }

    @Test
    @DisplayName("애플에 회원 토큰과 해당 client ID로 연결 해제를 요청한다")
    void revokeTokenWithMatchingClient() {
        var builder = RestClient.builder().baseUrl("https://appleid.apple.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var generator = mock(AppleClientSecretGenerator.class);
        when(generator.generate("apple-client")).thenReturn("client-secret");
        server.expect(requestTo("https://appleid.apple.com/auth/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("client_id=apple-client")))
                .andExpect(content().string(containsString("client_secret=client-secret")))
                .andExpect(content().string(containsString("token=apple-token")))
                .andRespond(withStatus(HttpStatus.OK));
        var revoker = new AppleTokenRevocationClient(generator, builder.build());

        assertThat(revoker.revoke("apple-client", "apple-token")).isEqualTo(Result.REVOKED);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500})
    @DisplayName("애플 오류는 토큰 없음이나 해제 성공으로 처리하지 않는다")
    void doNotTreatAppleFailureAsMissingOrRevoked(int status) {
        var builder = RestClient.builder().baseUrl("https://appleid.apple.com");
        var server = MockRestServiceServer.bindTo(builder).build();
        var generator = mock(AppleClientSecretGenerator.class);
        when(generator.generate("apple-client")).thenReturn("client-secret");
        server.expect(requestTo("https://appleid.apple.com/auth/revoke"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("apple-token"));
        var revoker = new AppleTokenRevocationClient(generator, builder.build());

        assertThatThrownBy(() -> revoker.revoke("apple-client", "apple-token"))
                .isInstanceOf(AppleTokenRevocationException.class)
                .hasNoCause();
        server.verify();
    }
}
