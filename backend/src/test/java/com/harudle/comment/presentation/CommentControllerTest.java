package com.harudle.comment.presentation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.auth.presentation.AuthenticationRequiredException;
import com.harudle.comment.service.CommentService;
import com.harudle.comment.service.dto.CommentPageResult;
import com.harudle.comment.service.dto.CommentResult;
import com.harudle.comment.service.exception.CommentAccessDeniedException;
import com.harudle.comment.service.exception.CommentIntegrationUnavailableException;
import com.harudle.comment.service.exception.CommentNotFoundException;
import com.harudle.comment.service.exception.InvalidCommentCursorException;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CommentSecurityConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.FeedLikeSecurityConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.profile.service.port.PublicProfileReader.Profile;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(CommentController.class)
@Import({AuthenticatedUserIdResolver.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, CsrfConfiguration.class, SecurityConfig.class, CommentSecurityConfiguration.class,
        FeedLikeSecurityConfiguration.class, CommentControllerTest.UnrelatedController.class})
class CommentControllerTest {
    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID FEED_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID COMMENT_ID = UUID.fromString("4a08c9a2-7531-4927-a898-6fe49cc303ea");
    private static final String LIST_PATH = "/api/v1/feeds/" + FEED_ID + "/comments";
    private static final String DELETE_PATH = "/api/v1/comments/" + COMMENT_ID;
    private static final String CSRF_TOKEN = "same-cookie-and-header";
    private static final Instant CREATED_AT = Instant.parse("2026-10-12T02:00:00Z");
    private static final Profile PROFILE = new Profile(USER_ID, "연준", URI.create("https://example.com/profile.svg"));

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CommentService service;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private OAuthLoginSuccessHandler successHandler;
    @MockitoBean private OAuthLoginFailureHandler failureHandler;
    @MockitoBean private ClientRegistrationRepository registrations;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("valid-token")).thenReturn(Jwt.withTokenValue("valid-token")
                .header("alg", "HS256").subject(USER_ID.toString()).issuedAt(CREATED_AT)
                .expiresAt(CREATED_AT.plusSeconds(1800)).build());
    }

    @Test
    void anonymousListUsesDefaultPageSizeAndNullViewer() throws Exception {
        when(service.list(FEED_ID, null, null, 20)).thenReturn(new CommentPageResult(
                List.of(comment("안녕", false)), null, false));
        mockMvc.perform(get(LIST_PATH)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items[0].author.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.items[0].author.nickname").value("연준"))
                .andExpect(jsonPath("$.items[0].author.profileImageUrl").value(PROFILE.profileImageUrl().toString()))
                .andExpect(jsonPath("$.items[0].isMine").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty()).andExpect(jsonPath("$.hasNext").value(false));
        verify(service).list(FEED_ID, null, null, 20);
    }

    @Test
    void authenticatedListPassesTheJwtViewerAndCursor() throws Exception {
        when(service.list(FEED_ID, USER_ID, "cursor-value", 1))
                .thenReturn(new CommentPageResult(List.of(comment("안녕", true)), "next", true));
        mockMvc.perform(bearer(get(LIST_PATH).param("cursor", "cursor-value").param("size", "1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].isMine").value(true))
                .andExpect(jsonPath("$.nextCursor").value("next")).andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    void invalidBearerOnPublicListDoesNotSilentlyBecomeAnonymous() throws Exception {
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("expired or invalid"));
        mockMvc.perform(get(LIST_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "text"})
    void rejectsInvalidPageSizes(String size) throws Exception {
        mockMvc.perform(get(LIST_PATH).param("size", size)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void returnsInvalidCursorAndMissingFeedAsDocumentedErrors() throws Exception {
        when(service.list(FEED_ID, null, "invalid", 20)).thenThrow(new InvalidCommentCursorException());
        mockMvc.perform(get(LIST_PATH).param("cursor", "invalid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        when(service.list(FEED_ID, null, null, 20)).thenThrow(new FeedNotFoundException());
        mockMvc.perform(get(LIST_PATH)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    void createsPlainTextCommentWithRealBearerAndCookieHeader() throws Exception {
        String text = "<script>안녕 😀</script>";
        when(service.create(USER_ID, FEED_ID, text)).thenReturn(comment(text, true));
        mockMvc.perform(withCsrf(bearer(post(LIST_PATH))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + text + "\"}"))
                .andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.LOCATION, DELETE_PATH))
                .andExpect(jsonPath("$.id").value(COMMENT_ID.toString()))
                .andExpect(jsonPath("$.content").value(text)).andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()));
        verify(service).create(USER_ID, FEED_ID, text);
    }

    @Test
    void acceptsExactly100UnicodeCodePointsRatherThanUtf16CodeUnits() throws Exception {
        String content = "😀".repeat(100);
        when(service.create(USER_ID, FEED_ID, content)).thenReturn(comment(content, true));
        mockMvc.perform(withCsrf(bearer(post(LIST_PATH))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isCreated());
        verify(service).create(USER_ID, FEED_ID, content);
    }

    @Test
    void rejectsBlankAnd101CodePointContentsWithFieldErrors() throws Exception {
        for (String invalid : List.of(" ", "😀".repeat(101))) {
            mockMvc.perform(withCsrf(bearer(post(LIST_PATH))).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"" + invalid + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value("content"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void rejectsNulAsFieldValidationErrorBeforePostgresStorage() throws Exception {
        mockMvc.perform(withCsrf(bearer(post(LIST_PATH))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\\u0000\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsUnpairedSurrogatesWithoutTreatingValidEmojiAsInvalidText() throws Exception {
        for (String invalid : new String[]{"\\ud800", "\\udc00", "앞\\ud800뒤"}) {
            mockMvc.perform(withCsrf(bearer(post(LIST_PATH))).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"" + invalid + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value("content"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void deletesOwnCommentWithNoResponseBody() throws Exception {
        mockMvc.perform(withCsrf(bearer(delete(DELETE_PATH))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).delete(USER_ID, COMMENT_ID);
    }

    @Test
    void deletesReturnCommentNotFoundAndOwnershipErrors() throws Exception {
        org.mockito.Mockito.doThrow(new CommentNotFoundException()).when(service).delete(USER_ID, COMMENT_ID);
        mockMvc.perform(withCsrf(bearer(delete(DELETE_PATH))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("urn:harudle:problem:comment-not-found"));
        org.mockito.Mockito.doThrow(new CommentAccessDeniedException()).when(service).delete(USER_ID, COMMENT_ID);
        mockMvc.perform(withCsrf(bearer(delete(DELETE_PATH))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE"})
    void validBearerDoesNotBypassCsrfForCommentMutations(String method) throws Exception {
        mockMvc.perform(bearer(mutation(method))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mockMvc.perform(bearer(mutation(method)).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mockMvc.perform(bearer(mutation(method)).cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN))
                        .header("X-XSRF-TOKEN", "mismatched"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "DELETE"})
    void unauthenticatedMutationsReturn401BeforeCsrf(String method) throws Exception {
        mockMvc.perform(mutation(method)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(withCsrf(mutation(method))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid"));
        mockMvc.perform(mutation(method).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void missingProfileIntegrationIsExplicit503ForListAndCreate() throws Exception {
        when(service.list(FEED_ID, null, null, 20)).thenThrow(new CommentIntegrationUnavailableException());
        mockMvc.perform(get(LIST_PATH)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMENT_UNAVAILABLE"));
        when(service.create(USER_ID, FEED_ID, "안녕")).thenThrow(new CommentIntegrationUnavailableException());
        mockMvc.perform(withCsrf(bearer(post(LIST_PATH))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("COMMENT_UNAVAILABLE"));
    }

    @Test
    void inactiveActorReturns401FromServiceWithoutChangingSecurityContract() throws Exception {
        when(service.create(USER_ID, FEED_ID, "안녕")).thenThrow(new AuthenticationRequiredException());
        mockMvc.perform(withCsrf(bearer(post(LIST_PATH))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"안녕\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void malformedPathIdsReturn400WithoutCallingService() throws Exception {
        mockMvc.perform(get("/api/v1/feeds/not-a-uuid/comments"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(withCsrf(bearer(delete("/api/v1/comments/not-a-uuid"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void scopedChainDoesNotChangeUnrelatedBearerMutationSecurity() throws Exception {
        mockMvc.perform(bearer(put("/api/v1/comment-security-probe")))
                .andExpect(status().isOk()).andExpect(content().string("unchanged"));
    }

    private CommentResult comment(String content, boolean isMine) {
        return new CommentResult(COMMENT_ID, PROFILE, content, CREATED_AT, isMine);
    }

    private MockHttpServletRequestBuilder mutation(String method) {
        return "POST".equals(method) ? post(LIST_PATH) : delete(DELETE_PATH);
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    @RestController
    static class UnrelatedController {
        @PutMapping("/api/v1/comment-security-probe")
        String probe() { return "unchanged"; }
    }
}
