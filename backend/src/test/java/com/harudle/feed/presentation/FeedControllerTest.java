package com.harudle.feed.presentation;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.category.service.exception.CategoryInactiveException;
import com.harudle.category.service.exception.CategoryNotFoundException;
import com.harudle.category.service.port.CategoryReader;
import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.diary.service.exception.DiaryNotFoundException;
import com.harudle.diary.service.exception.DiaryNotPublishableException;
import com.harudle.feed.configuration.FeedConfiguration;
import com.harudle.feed.query.FeedSort;
import com.harudle.feed.service.FeedDeletionService;
import com.harudle.feed.service.FeedListService;
import com.harudle.feed.service.FeedPublicationService;
import com.harudle.feed.service.FeedQueryService;
import com.harudle.feed.service.dto.FeedPageResult;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.feed.service.exception.DiaryAlreadyPublishedException;
import com.harudle.feed.service.exception.FeedAccessDeniedException;
import com.harudle.feed.service.exception.FeedIntegrationUnavailableException;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.exception.InvalidFeedCursorException;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.profile.service.port.PublicProfileReader;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FeedController.class)
@Import({
        FeedResponseAssembler.class, FeedConfiguration.class, AuthenticatedUserIdResolver.class,
        ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class, TraceIdConfiguration.class,
        CsrfConfiguration.class, SecurityConfig.class, TimeConfiguration.class
})
@TestPropertySource(properties = "harudle.feed.public-base-url=https://harudle.example/feeds/")
class FeedControllerTest {

    private static final UUID FEED = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID ACTOR = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID DIARY = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
    private static final String REQUEST = """
            {"diaryId":"550e8400-e29b-41d4-a716-446655440002","categoryId":1}
            """;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private FeedPublicationService publicationService;
    @MockitoBean private FeedQueryService queryService;
    @MockitoBean private FeedListService listService;
    @MockitoBean private FeedDeletionService deletionService;
    @MockitoBean private ImageUrlProvider imageUrlProvider;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private OAuthLoginSuccessHandler loginSuccessHandler;
    @MockitoBean private OAuthLoginFailureHandler loginFailureHandler;
    @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

    @BeforeEach
    void setUp() {
        when(imageUrlProvider.createAccessUrl("generated/comic.webp")).thenReturn(new ImageAccessUrl(
                URI.create("https://signed.example/comic.webp"), Instant.parse("2026-10-11T01:15:00Z")));
    }

    @Test
    void publishesWithCreatedLocationAndPublicResponse() throws Exception {
        when(publicationService.publish(ACTOR, DIARY, 1)).thenReturn(result(false, true));
        mockMvc.perform(post("/api/v1/feeds")
                        .with(jwt().jwt(token -> token.subject(ACTOR.toString())))
                        .cookie(csrfCookie()).header("X-XSRF-TOKEN", "feed-test-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/feeds/" + FEED))
                .andExpect(jsonPath("$.id").value(FEED.toString()))
                .andExpect(jsonPath("$.category.id").value(1))
                .andExpect(jsonPath("$.author.nickname").value("캐모"))
                .andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.shareUrl").value("https://harudle.example/feeds/" + FEED))
                .andExpect(jsonPath("$.imageUrlExpiresAt").value("2026-10-11T10:15:00+09:00"))
                .andExpect(jsonPath("$.sourceText").doesNotExist())
                .andExpect(jsonPath("$.diaryId").doesNotExist())
                .andExpect(jsonPath("$.author.email").doesNotExist())
                .andExpect(jsonPath("$.imageObjectKey").doesNotExist());
    }

    @Test
    void publiclyReadsWithoutTokenOrCsrf() throws Exception {
        when(queryService.getDetail(isNull(), eq(FEED))).thenReturn(result(false, false));
        mockMvc.perform(get("/api/v1/feeds/{feedId}", FEED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedByMe").value(false))
                .andExpect(jsonPath("$.isMine").value(false))
                .andExpect(jsonPath("$.likeCount").value(12))
                .andExpect(jsonPath("$.commentCount").value(3))
                .andExpect(jsonPath("$.shareUrl").value("https://harudle.example/feeds/" + FEED))
                .andExpect(jsonPath("$.publishedAt").value("2026-10-11T10:00:00+09:00"));
        verify(queryService).getDetail(null, FEED);
    }

    @Test
    void personalizesAuthenticatedPublicRead() throws Exception {
        when(queryService.getDetail(ACTOR, FEED)).thenReturn(result(true, true));
        mockMvc.perform(get("/api/v1/feeds/{feedId}", FEED)
                        .with(jwt().jwt(token -> token.subject(ACTOR.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedByMe").value(true))
                .andExpect(jsonPath("$.isMine").value(true));
    }

    @Test
    void publiclyListsWithDefaultSortAndSizeWithoutCsrf() throws Exception {
        when(listService.getList(null, FeedSort.LATEST, null, null, 20))
                .thenReturn(new FeedPageResult(List.of(result(false, false)), "next-page", true));
        mockMvc.perform(get("/api/v1/feeds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(FEED.toString()))
                .andExpect(jsonPath("$.items[0].likedByMe").value(false))
                .andExpect(jsonPath("$.items[0].shareUrl").value("https://harudle.example/feeds/" + FEED))
                .andExpect(jsonPath("$.items[0].sourceText").doesNotExist())
                .andExpect(jsonPath("$.items[0].imageObjectKey").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("next-page"))
                .andExpect(jsonPath("$.hasNext").value(true));
        verify(listService).getList(null, FeedSort.LATEST, null, null, 20);
    }

    @Test
    void acceptsAuthenticatedPopularListAndCategoryCursorParameters() throws Exception {
        when(listService.getList(ACTOR, FeedSort.POPULAR, 1L, "previous-page", 50))
                .thenReturn(new FeedPageResult(List.of(result(true, true)), null, false));
        mockMvc.perform(get("/api/v1/feeds").param("sort", "POPULAR").param("categoryId", "1")
                        .param("cursor", "previous-page").param("size", "50")
                        .with(jwt().jwt(token -> token.subject(ACTOR.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.items[0].isMine").value(true))
                .andExpect(jsonPath("$.hasNext").value(false));
        verify(listService).getList(ACTOR, FeedSort.POPULAR, 1L, "previous-page", 50);
    }

    @Test
    void returnsEmptyListWithNullCursor() throws Exception {
        when(listService.getList(null, FeedSort.LATEST, null, null, 20))
                .thenReturn(new FeedPageResult(List.of(), null, false));
        mockMvc.perform(get("/api/v1/feeds")).andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[],\"nextCursor\":null,\"hasNext\":false}"));
    }

    @ParameterizedTest
    @MethodSource("invalidListParameters")
    void validatesListQueryParameters(String parameter, String value) throws Exception {
        mockMvc.perform(get("/api/v1/feeds").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(listService);
    }

    private static Stream<Arguments> invalidListParameters() {
        return Stream.of(Arguments.of("sort", "UNKNOWN"), Arguments.of("size", "0"),
                Arguments.of("size", "51"), Arguments.of("size", "bad"),
                Arguments.of("categoryId", "0"), Arguments.of("categoryId", "-1"),
                Arguments.of("categoryId", "UUID"));
    }

    @Test
    void mapsInvalidCursorToProblemDetails() throws Exception {
        when(listService.getList(null, FeedSort.LATEST, null, "invalid", 20))
                .thenThrow(new InvalidFeedCursorException());
        mockMvc.perform(get("/api/v1/feeds").param("cursor", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void mapsMissingCategoryOnEmptyList() throws Exception {
        when(listService.getList(null, FeedSort.LATEST, 99L, null, 20))
                .thenThrow(new CategoryNotFoundException());
        mockMvc.perform(get("/api/v1/feeds").param("categoryId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void rejectsMissingAuthenticationOnPublication() throws Exception {
        mockMvc.perform(post("/api/v1/feeds")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(publicationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "COOKIE_ONLY", "HEADER_ONLY", "MISMATCHED", "MATCHED"})
    void publishesWithBearerRegardlessOfCsrfTokens(String suppliedToken) throws Exception {
        when(jwtDecoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid")
                .header("alg", "RS256").subject(ACTOR.toString()).build());
        when(publicationService.publish(ACTOR, DIARY, 1)).thenReturn(result(false, true));
        var request = post("/api/v1/feeds").header("Authorization", "Bearer valid")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST);
        if ("COOKIE_ONLY".equals(suppliedToken) || "MISMATCHED".equals(suppliedToken)
                || "MATCHED".equals(suppliedToken)) {
            request.cookie(csrfCookie());
        }
        if ("HEADER_ONLY".equals(suppliedToken) || "MISMATCHED".equals(suppliedToken)
                || "MATCHED".equals(suppliedToken)) {
            request.header("X-XSRF-TOKEN", "MISMATCHED".equals(suppliedToken) ? "wrong-token" : "feed-test-csrf");
        }
        mockMvc.perform(request).andExpect(status().isCreated());
        verify(publicationService).publish(ACTOR, DIARY, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"categoryId\":1}",
            "{\"diaryId\":\"invalid\",\"categoryId\":1}",
            "{\"diaryId\":\"550e8400-e29b-41d4-a716-446655440002\",\"categoryId\":0}",
            "{\"diaryId\":\"550e8400-e29b-41d4-a716-446655440002\",\"categoryId\":\"UUID\"}"
    })
    void validatesPublicationBody(String body) throws Exception {
        mockMvc.perform(post("/api/v1/feeds")
                        .with(jwt().jwt(token -> token.subject(ACTOR.toString())))
                        .cookie(csrfCookie()).header("X-XSRF-TOKEN", "feed-test-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(publicationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "1-1-1-1-1"})
    void validatesCanonicalFeedUuid(String feedId) throws Exception {
        mockMvc.perform(get("/api/v1/feeds/{feedId}", feedId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(queryService);
    }

    @ParameterizedTest
    @MethodSource("publicationFailures")
    void mapsPublicationErrors(RuntimeException error, int statusCode, String code) throws Exception {
        when(publicationService.publish(ACTOR, DIARY, 1)).thenThrow(error);
        mockMvc.perform(post("/api/v1/feeds")
                        .with(jwt().jwt(token -> token.subject(ACTOR.toString())))
                        .cookie(csrfCookie()).header("X-XSRF-TOKEN", "feed-test-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/feeds"));
    }

    @Test
    void returns404ForDeletedOrMissingFeed() throws Exception {
        when(queryService.getDetail(isNull(), eq(FEED))).thenThrow(new FeedNotFoundException());
        mockMvc.perform(get("/api/v1/feeds/{feedId}", FEED))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    void doesNotExposeLegacyPublicShareApi() throws Exception {
        mockMvc.perform(get("/api/v1/public/shares/{shareId}", FEED))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_NOT_FOUND"));
        verifyNoInteractions(queryService, imageUrlProvider);
    }

    @Test
    void rejectsExpiredBearerBeforePublicReadSoClientCanRefreshOrRetryAnonymously() throws Exception {
        when(jwtDecoder.decode("expired")).thenThrow(new BadJwtException("expired"));
        mockMvc.perform(get("/api/v1/feeds/{feedId}", FEED).header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
        verifyNoInteractions(queryService);
    }

    private static Stream<Arguments> publicationFailures() {
        return Stream.of(
                Arguments.of(new DiaryAccessDeniedException(), 403, "FORBIDDEN"),
                Arguments.of(new DiaryNotFoundException(), 404, "DIARY_NOT_FOUND"),
                Arguments.of(new CategoryNotFoundException(), 404, "CATEGORY_NOT_FOUND"),
                Arguments.of(new CategoryInactiveException(), 409, "CATEGORY_INACTIVE"),
                Arguments.of(new DiaryNotPublishableException(), 409, "DIARY_NOT_PUBLISHABLE"),
                Arguments.of(new DiaryAlreadyPublishedException(), 409, "DIARY_ALREADY_PUBLISHED"),
                Arguments.of(new FeedIntegrationUnavailableException(CategoryReader.class), 503, "FEED_UNAVAILABLE")
        );
    }

    @Test
    void deletesWithRealBearerWithoutCsrf() throws Exception {
        when(jwtDecoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid")
                .header("alg", "RS256").subject(ACTOR.toString()).build());
        mockMvc.perform(delete("/api/v1/feeds/{feedId}", FEED).header("Authorization", "Bearer valid"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(deletionService).delete(ACTOR, FEED);
        verifyNoInteractions(imageUrlProvider, publicationService, queryService, listService);
    }

    @Test
    void requiresAuthenticationOnDeletion() throws Exception {
        mockMvc.perform(delete("/api/v1/feeds/{feedId}", FEED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(deletionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "COOKIE_ONLY", "HEADER_ONLY", "MISMATCHED", "MATCHED"})
    void deletesWithBearerRegardlessOfCsrfTokens(String suppliedToken) throws Exception {
        when(jwtDecoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid")
                .header("alg", "RS256").subject(ACTOR.toString()).build());
        var request = delete("/api/v1/feeds/{feedId}", FEED).header("Authorization", "Bearer valid");
        if ("COOKIE_ONLY".equals(suppliedToken) || "MISMATCHED".equals(suppliedToken)
                || "MATCHED".equals(suppliedToken)) {
            request.cookie(csrfCookie());
        }
        if ("HEADER_ONLY".equals(suppliedToken) || "MISMATCHED".equals(suppliedToken)
                || "MATCHED".equals(suppliedToken)) {
            request.header("X-XSRF-TOKEN", "MISMATCHED".equals(suppliedToken) ? "wrong-token" : "feed-test-csrf");
        }
        mockMvc.perform(request).andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(deletionService).delete(ACTOR, FEED);
    }

    @Test
    void rejectsExpiredBearerOnDeletion() throws Exception {
        when(jwtDecoder.decode("expired")).thenThrow(new BadJwtException("expired"));
        mockMvc.perform(delete("/api/v1/feeds/{feedId}", FEED).header("Authorization", "Bearer expired")
                        .cookie(csrfCookie()).header("X-XSRF-TOKEN", "feed-test-csrf"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
        verifyNoInteractions(deletionService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "1-1-1-1-1"})
    void validatesCanonicalFeedUuidOnDeletion(String feedId) throws Exception {
        mockMvc.perform(delete("/api/v1/feeds/{feedId}", feedId)
                        .with(jwt().jwt(token -> token.subject(ACTOR.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(deletionService);
    }

    @ParameterizedTest
    @MethodSource("deletionFailures")
    void mapsDeletionFailuresToProblemDetails(RuntimeException error, int statusCode, String code) throws Exception {
        doThrow(error).when(deletionService).delete(ACTOR, FEED);
        mockMvc.perform(delete("/api/v1/feeds/{feedId}", FEED)
                        .with(jwt().jwt(token -> token.subject(ACTOR.toString()))))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/feeds/" + FEED));
    }

    private static Stream<Arguments> deletionFailures() {
        return Stream.of(
                Arguments.of(new FeedAccessDeniedException(), 403, "FORBIDDEN"),
                Arguments.of(new FeedNotFoundException(), 404, "FEED_NOT_FOUND"),
                Arguments.of(new FeedIntegrationUnavailableException(com.harudle.push.service.port.FeedPushOutbox.class),
                        503, "FEED_UNAVAILABLE"),
                Arguments.of(new IllegalStateException("푸시 취소 저장 실패"), 500, "INTERNAL_SERVER_ERROR")
        );
    }

    private static Cookie csrfCookie() {
        return new Cookie("XSRF-TOKEN", "feed-test-csrf");
    }

    private static FeedResult result(boolean liked, boolean mine) {
        return new FeedResult(FEED,
                new PublicProfileReader.Profile(ACTOR, "캐모", URI.create("https://example.com/profile.png")),
                new CategoryReader.Category(1, "일상", 0, true),
                "generated/comic.webp", Instant.parse("2026-10-11T01:00:00Z"), 12, 3, liked, mine);
    }
}
