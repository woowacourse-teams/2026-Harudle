package com.harudle.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

class AdminAuthorizationManagerTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminAuthorizationManager authorizationManager = new AdminAuthorizationManager(userRepository);
    private final RequestAuthorizationContext context = new RequestAuthorizationContext(
            new MockHttpServletRequest()
    );

    @Test
    @DisplayName("활성 관리자에게 접근을 허용한다")
    void grantsActiveAdmin() {
        Authentication authentication = authenticatedUser();
        User user = mock(User.class);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(user.isDeleted()).thenReturn(false);
        when(user.isAdmin()).thenReturn(true);

        AuthorizationResult result = authorizationManager.authorize(() -> authentication, context);

        assertThat(result.isGranted()).isTrue();
    }

    @Test
    @DisplayName("일반 사용자의 접근을 거부한다")
    void deniesRegularUser() {
        Authentication authentication = authenticatedUser();
        User user = mock(User.class);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(user.isDeleted()).thenReturn(false);
        when(user.isAdmin()).thenReturn(false);

        AuthorizationResult result = authorizationManager.authorize(() -> authentication, context);

        assertThat(result.isGranted()).isFalse();
    }

    @Test
    @DisplayName("탈퇴한 관리자의 접근을 거부한다")
    void deniesDeletedAdmin() {
        Authentication authentication = authenticatedUser();
        User user = mock(User.class);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(user.isDeleted()).thenReturn(true);

        AuthorizationResult result = authorizationManager.authorize(() -> authentication, context);

        assertThat(result.isGranted()).isFalse();
    }

    @Test
    @DisplayName("유효한 사용자 ID가 없는 인증의 접근을 거부한다")
    void deniesInvalidPrincipal() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn("invalid-user-id");

        AuthorizationResult result = authorizationManager.authorize(() -> authentication, context);

        assertThat(result.isGranted()).isFalse();
    }

    private Authentication authenticatedUser() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(USER_ID.toString());
        return authentication;
    }
}
