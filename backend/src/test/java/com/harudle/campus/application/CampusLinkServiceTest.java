package com.harudle.campus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.AccountSuspendedException;
import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.application.CurrentUserService;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.UserRole;
import com.harudle.auth.domain.exception.PublicProfileNotAssignedException;
import com.harudle.campus.infrastructure.HmacCampusLinkTokenSigner;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CampusLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final String KEY = "Y2FtcHVzLXNpZ25pbmcta2V5LWZvci10ZXN0LTAxMjM0NTY3ODk=";
    private static final String STATE = "s".repeat(43);
    private static final UUID USER_ID = UUID.fromString("6d64421d-6a44-48fa-b917-c1d20f5e5883");
    private static final URI REDIRECT = URI.create("https://campus.harudle.com/auth/harudle/callback");
    private static final URI DEFAULT_IMAGE = URI.create("https://www.harudle.com/profile-images/v1/5.png");

    private CurrentUserService currentUserService;
    private AccountAccessPolicy accountAccess;
    private CampusLinkService service;

    @BeforeEach
    void setUp() {
        currentUserService = mock(CurrentUserService.class);
        accountAccess = mock(AccountAccessPolicy.class);
        CampusLinkProperties properties = new CampusLinkProperties(true, REDIRECT, KEY, null);
        service = new CampusLinkService(
                properties,
                currentUserService,
                accountAccess,
                new HmacCampusLinkTokenSigner(properties)
        );
    }

    @Test
    @DisplayName("회원 ID · 닉네임 · 기본 프로필 사진 · state를 담은 60초짜리 서명 토큰을 발급한다")
    void issuesSignedToken() throws Exception {
        when(currentUserService.find(USER_ID)).thenReturn(user("눈누난나", DEFAULT_IMAGE, null));

        IssuedCampusLinkToken issued = service.issue(USER_ID, STATE, NOW);

        SignedJWT jwt = SignedJWT.parse(issued.token());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.verify(new MACVerifier(Base64.getDecoder().decode(KEY)))).isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("harudle");
        assertThat(claims.getAudience()).isEqualTo(List.of("harudle-campus"));
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(claims.getStringClaim("nickname")).isEqualTo("눈누난나");
        assertThat(claims.getStringClaim("picture")).isEqualTo(DEFAULT_IMAGE.toString());
        assertThat(claims.getStringClaim("state")).isEqualTo(STATE);
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getExpirationTime()).isEqualTo(Date.from(NOW.plusSeconds(60)));
        assertThat(claims.getClaims()).doesNotContainKeys("email", "name");
        assertThat(issued.redirectUri()).isEqualTo(REDIRECT);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("곧 만료되는 직접 올린 사진 주소는 토큰에 넣지 않는다")
    void omitsExpiringProfileImage() throws Exception {
        URI signed = URI.create("https://s3.example/private.webp?signature=1");
        when(currentUserService.find(USER_ID)).thenReturn(user("눈누난나", signed, NOW.plusSeconds(900)));

        IssuedCampusLinkToken issued = service.issue(USER_ID, STATE, NOW);

        assertThat(SignedJWT.parse(issued.token()).getJWTClaimsSet().getStringClaim("picture")).isNull();
    }

    @Test
    @DisplayName("공개 프로필(닉네임)이 없으면 발급하지 않는다")
    void rejectsWithoutNickname() {
        when(currentUserService.find(USER_ID)).thenReturn(user(null, null, null));

        assertThatThrownBy(() -> service.issue(USER_ID, STATE, NOW))
                .isInstanceOf(PublicProfileNotAssignedException.class);
    }

    @Test
    @DisplayName("정지된 회원에게는 발급하지 않는다")
    void rejectsSuspendedUser() {
        doThrow(new AccountSuspendedException()).when(accountAccess).requireAllowedForRequest(USER_ID);

        assertThatThrownBy(() -> service.issue(USER_ID, STATE, NOW))
                .isInstanceOf(AccountSuspendedException.class);
    }

    @Test
    @DisplayName("켜져 있을 때 콜백 주소와 32바이트 이상 서명 키가 없으면 설정 오류다")
    void validatesEnabledProperties() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new CampusLinkProperties(true, null, KEY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampusLinkProperties(true, REDIRECT, shortKey, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CampusLinkProperties(true, REDIRECT, "not base64!", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CampusLinkProperties(false, null, null, null).tokenTtl()).isEqualTo(Duration.ofSeconds(60));
    }

    private CurrentUserResult user(String nickname, URI image, Instant imageExpiresAt) {
        return new CurrentUserResult(
                USER_ID,
                "하루들",
                nickname,
                image,
                imageExpiresAt,
                "user@example.com",
                UserRole.USER,
                OAuthProvider.KAKAO,
                NOW
        );
    }
}
