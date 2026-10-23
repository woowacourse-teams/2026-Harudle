package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.OAuthLoginCommand;
import com.harudle.auth.domain.OAuthProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OAuthLoginCommandResolverTest {

    @ParameterizedTest
    @CsvSource({"kakao,KAKAO", "google,GOOGLE", "apple,APPLE"})
    @DisplayName("제공자를 지원하는 변환 객체에만 로그인 명령 생성을 요청한다")
    void delegatesToSupportingMapper(String registrationId, OAuthProvider provider) {
        OAuthLoginCommandMapper unsupportedMapper = mock(OAuthLoginCommandMapper.class);
        OAuthLoginCommandMapper supportedMapper = mock(OAuthLoginCommandMapper.class);
        OAuthLoginCommandResolver resolver = new OAuthLoginCommandResolver(
                List.of(unsupportedMapper, supportedMapper)
        );
        Map<String, Object> attributes = Map.of("id", "12345");
        OAuthLoginCommand command = new OAuthLoginCommand(provider, "12345", null, null);
        when(supportedMapper.supports(registrationId)).thenReturn(true);
        when(supportedMapper.map(attributes)).thenReturn(command);

        OAuthLoginCommand result = resolver.resolve(registrationId, attributes);

        assertThat(result).isSameAs(command);
        verify(supportedMapper).map(attributes);
        verify(unsupportedMapper, never()).map(any());
    }

    @Test
    @DisplayName("제공자를 지원하는 변환 객체가 없으면 로그인 명령을 생성하지 않는다")
    void rejectsUnsupportedProvider() {
        OAuthLoginCommandMapper mapper = mock(OAuthLoginCommandMapper.class);
        OAuthLoginCommandResolver resolver = new OAuthLoginCommandResolver(List.of(mapper));

        assertThatThrownBy(() -> resolver.resolve("apple", Map.of()))
                .isInstanceOf(UnsupportedOAuthProviderException.class);

        verify(mapper, never()).map(any());
    }

    @Test
    @DisplayName("동일 제공자를 지원하는 변환 객체가 여러 개면 임의로 선택하지 않는다")
    void rejectsDuplicateSupportingMappers() {
        OAuthLoginCommandMapper firstMapper = mock(OAuthLoginCommandMapper.class);
        OAuthLoginCommandMapper secondMapper = mock(OAuthLoginCommandMapper.class);
        OAuthLoginCommandResolver resolver = new OAuthLoginCommandResolver(List.of(firstMapper, secondMapper));
        when(firstMapper.supports("google")).thenReturn(true);
        when(secondMapper.supports("google")).thenReturn(true);

        assertThatThrownBy(() -> resolver.resolve("google", Map.of("sub", "12345")))
                .isInstanceOf(OAuthLoginConsistencyException.class);

        verify(firstMapper, never()).map(any());
        verify(secondMapper, never()).map(any());
    }

}
