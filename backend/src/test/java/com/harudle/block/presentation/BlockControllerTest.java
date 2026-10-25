package com.harudle.block.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.block.domain.InvalidBlockException;
import com.harudle.block.service.BlockService;
import com.harudle.block.service.dto.BlockPageResult;
import com.harudle.block.service.dto.BlockedUserResult;
import com.harudle.block.service.exception.BlockTargetNotFoundException;
import com.harudle.block.service.exception.BlockUnavailableException;
import com.harudle.block.service.exception.InvalidBlockCursorException;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.BlockSecurityConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BlockController.class)
@Import({AuthenticatedUserIdResolver.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, CsrfConfiguration.class, SecurityConfig.class, BlockSecurityConfiguration.class})
class BlockControllerTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final String PATH = "/api/v1/blocks";
    private static final String CSRF = "matching-cookie-header";
    @Autowired private MockMvc mockMvc;
    @MockitoBean private BlockService service;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private OAuthLoginSuccessHandler successHandler;
    @MockitoBean private OAuthLoginFailureHandler failureHandler;
    @MockitoBean private ClientRegistrationRepository registrations;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        when(jwtDecoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid").header("alg", "HS256")
                .subject(ACTOR.toString()).build());
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("차단과 해제는 인증 주체를 사용하고 상대 상태 없이 204로 응답한다")
    void mutatesUsingAuthenticatedActorWithoutExposingOppositeDirection() {
        var put = authorized().put(PATH + "/" + TARGET + "?blockerId=" + TARGET);
        var delete = authorized().delete(PATH + "/" + TARGET);
        assertThat(put.statusCode()).isEqualTo(204);
        assertThat(delete.statusCode()).isEqualTo(204);
        assertThat(put.asString()).isEmpty();
        assertThat(delete.asString()).isEmpty();
        assertThat(put.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        verify(service).block(ACTOR, TARGET);
        verify(service).unblock(ACTOR, TARGET);
    }

    @Test
    @DisplayName("목록은 본인 발신 항목만 담은 계약을 반환하며 GET에 CSRF를 요구하지 않는다")
    void listsOwnEntriesWithoutMutationCsrf() {
        when(service.list(ACTOR, null, 20)).thenReturn(new BlockPageResult(List.of(
                new BlockedUserResult(TARGET, "탈퇴한 사용자", null, Instant.parse("2026-10-22T00:00:00Z"))),
                null, false));
        var response = RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, "Bearer valid").get(PATH);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.jsonPath().getMap("$")).containsOnlyKeys("items", "nextCursor", "hasNext");
        assertThat(response.jsonPath().getMap("items[0]"))
                .containsOnlyKeys("userId", "nickname", "profileImageUrl", "blockedAt");
        assertThat(response.jsonPath().getString("items[0].userId")).isEqualTo(TARGET.toString());
    }

    @Test
    @DisplayName("비로그인과 잘못된 Bearer는 CSRF보다 먼저 401로 거절한다")
    void rejectsUnauthenticatedRequestsBeforeCsrf() {
        assertThat(RestAssuredMockMvc.given().put(PATH + "/" + TARGET).statusCode()).isEqualTo(401);
        assertThat(RestAssuredMockMvc.given().get(PATH).statusCode()).isEqualTo(401);
        when(jwtDecoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
        assertThat(RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .delete(PATH + "/" + TARGET).statusCode()).isEqualTo(401);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("차단과 해제는 인증 뒤에도 CSRF가 없거나 다르면 서비스를 호출하지 않는다")
    void rejectsMissingAndMismatchedCsrf() {
        var missing = RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, "Bearer valid")
                .put(PATH + "/" + TARGET);
        var mismatch = authorized("different").delete(PATH + "/" + TARGET);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(mismatch.statusCode()).isEqualTo(403);
        assertThat(missing.jsonPath().getString("code")).isEqualTo("INVALID_CSRF_TOKEN");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "invalid"})
    @DisplayName("목록 크기 범위와 숫자 형식은 400으로 거절한다")
    void validatesPageSize(String size) {
        var response = authorized().get(PATH + "?size=" + size);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1-1-1-1-1",
            "00000000-0000-0000-0000-00000000000g",
            " 00000000-0000-0000-0000-000000000001 "
    })
    @DisplayName("차단과 해제의 대상 ID가 정규 UUID 형식이 아니면 서비스 호출 없이 400으로 거절한다")
    void rejectsNonCanonicalTargetWithoutCallingService(String userId) {
        var put = authorized().put(PATH + "/{userId}", userId);
        var delete = authorized().delete(PATH + "/{userId}", userId);

        assertThat(put.statusCode()).isEqualTo(400);
        assertThat(delete.statusCode()).isEqualTo(400);
        assertThat(put.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(delete.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "11111111-abcd-4abc-8abc-111111111111",
            "11111111-ABCD-4ABC-8ABC-111111111111"
    })
    @DisplayName("정규 길이의 소문자와 대문자 대상 UUID는 같은 회원 ID로 차단과 해제를 처리한다")
    void acceptsCanonicalTargetRegardlessOfHexLetterCase(String userId) {
        UUID target = UUID.fromString(userId);

        assertThat(authorized().put(PATH + "/{userId}", userId).statusCode()).isEqualTo(204);
        assertThat(authorized().delete(PATH + "/{userId}", userId).statusCode()).isEqualTo(204);
        verify(service).block(ACTOR, target);
        verify(service).unblock(ACTOR, target);
    }

    @Test
    @DisplayName("잘못된 커서는 검증 오류를 반환한다")
    void validatesCursor() {
        when(service.list(eq(ACTOR), any(), anyInt())).thenThrow(new InvalidBlockCursorException());
        var response = authorized().get(PATH + "?cursor=invalid");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CURSOR");
    }

    @Test
    @DisplayName("자기 차단과 없는 대상 및 프로필 장애를 고정된 오류로 응답한다")
    void mapsBusinessErrors() {
        doThrow(new InvalidBlockException()).when(service).block(ACTOR, TARGET);
        assertThat(authorized().put(PATH + "/" + TARGET).jsonPath().getString("code"))
                .isEqualTo("INVALID_BLOCK");
        doThrow(new BlockTargetNotFoundException()).when(service).block(ACTOR, TARGET);
        assertThat(authorized().put(PATH + "/" + TARGET).statusCode()).isEqualTo(404);
        when(service.list(eq(ACTOR), any(), anyInt())).thenThrow(new BlockUnavailableException());
        assertThat(authorized().get(PATH).statusCode()).isEqualTo(503);
    }

    private MockMvcRequestSpecification authorized() {
        return authorized(CSRF);
    }

    private MockMvcRequestSpecification authorized(String csrfHeader) {
        return RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, "Bearer valid")
                .header("X-XSRF-TOKEN", csrfHeader).postProcessors(request -> {
                    request.setCookies(new Cookie("XSRF-TOKEN", CSRF));
                    return request;
                });
    }
}
