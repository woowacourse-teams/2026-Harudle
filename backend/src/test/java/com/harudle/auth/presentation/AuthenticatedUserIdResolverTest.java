package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

class AuthenticatedUserIdResolverTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");

    private final AuthenticatedUserIdResolver resolver = new AuthenticatedUserIdResolver(
            new AuthenticationTrustResolverImpl()
    );

    @Test
    @DisplayName("인증된 principal의 UUID를 사용자 ID로 반환한다")
    void resolveAuthenticatedUserId() {
        Authentication authentication = authenticated(USER_ID.toString().toUpperCase());

        UUID resolvedUserId = resolver.resolve(authentication);

        assertThat(resolvedUserId).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("익명 인증은 인증 정보 없음으로 처리한다")
    void rejectAnonymousAuthentication() {
        Authentication authentication = new AnonymousAuthenticationToken(
                "anonymous-key",
                "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")
        );

        assertThatThrownBy(() -> resolver.resolve(authentication))
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    @DisplayName("인증되지 않은 principal은 인증 정보 없음으로 처리한다")
    void rejectUnauthenticatedPrincipal() {
        Authentication authentication = UsernamePasswordAuthenticationToken.unauthenticated(
                USER_ID.toString(),
                null
        );

        assertThatThrownBy(() -> resolver.resolve(authentication))
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    @DisplayName("표준 UUID 형식이 아닌 principal은 인증 정보 없음으로 처리한다")
    void rejectNonCanonicalUserId() {
        Authentication authentication = authenticated("1-1-1-1-1");

        assertThatThrownBy(() -> resolver.resolve(authentication))
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    @DisplayName("Authentication이 없으면 인증 정보 없음으로 처리한다")
    void rejectMissingAuthentication() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    private Authentication authenticated(String principalName) {
        return UsernamePasswordAuthenticationToken.authenticated(
                principalName,
                null,
                List.of()
        );
    }
}
