package com.harudle.notification.presentation;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CommentSecurityConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.FeedLikeSecurityConfiguration;
import com.harudle.common.security.NotificationSecurityConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.notification.service.NotificationService;
import com.harudle.notification.service.dto.NotificationPageResult;
import com.harudle.notification.service.dto.NotificationResult;
import com.harudle.notification.service.exception.InvalidNotificationCursorException;
import com.harudle.notification.service.exception.NotificationAccessDeniedException;
import com.harudle.notification.service.exception.NotificationIntegrationUnavailableException;
import com.harudle.notification.service.exception.NotificationNotFoundException;
import com.harudle.notification.service.port.InAppNotificationRecorder.Type;
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

@WebMvcTest(NotificationController.class)
@Import({AuthenticatedUserIdResolver.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, CsrfConfiguration.class, SecurityConfig.class, NotificationSecurityConfiguration.class,
        CommentSecurityConfiguration.class, FeedLikeSecurityConfiguration.class,
        NotificationControllerTest.UnrelatedController.class})
class NotificationControllerTest {
    private static final UUID USER = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID FEED = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final UUID NOTIFICATION = UUID.fromString("4a08c9a2-7531-4927-a898-6fe49cc303ea");
    private static final String LIST = "/api/v1/notifications";
    private static final String UPDATE = LIST + "/" + NOTIFICATION;
    private static final String CSRF = "same-cookie-and-header";
    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final Profile PROFILE = new Profile(
            USER, "네오", URI.create("https://example.com/profile.webp?signature=example"), NOW.plusSeconds(900)
    );

    @Autowired private MockMvc mockMvc;
    @MockitoBean private NotificationService service;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private OAuthLoginSuccessHandler successHandler;
    @MockitoBean private OAuthLoginFailureHandler failureHandler;
    @MockitoBean private ClientRegistrationRepository registrations;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("valid-token")).thenReturn(Jwt.withTokenValue("valid-token")
                .header("alg", "HS256").subject(USER.toString()).issuedAt(NOW).expiresAt(NOW.plusSeconds(1800)).build());
    }

    @Test
    void getsOwnNotificationPageAndGlobalUnreadCountWithDefaultSize() throws Exception {
        when(service.list(USER, null, 20)).thenReturn(new NotificationPageResult(List.of(
                new NotificationResult(NOTIFICATION, Type.COMMENT, FEED, PROFILE,
                        "네오님이 댓글을 남겼습니다.", NOW, false)), null, false, 12));
        mockMvc.perform(bearer(get(LIST))).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items[0].id").value(NOTIFICATION.toString()))
                .andExpect(jsonPath("$.items[0].type").value("COMMENT"))
                .andExpect(jsonPath("$.items[0].feedId").value(FEED.toString()))
                .andExpect(jsonPath("$.items[0].actor.nickname").value("네오"))
                .andExpect(jsonPath("$.items[0].actor.profileImageUrl").value(PROFILE.profileImageUrl().toString()))
                .andExpect(jsonPath("$.items[0].actor.profileImageUrlExpiresAt")
                        .value(PROFILE.profileImageUrlExpiresAt().toString()))
                .andExpect(jsonPath("$.items[0].message").value("네오님이 댓글을 남겼습니다."))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.unreadCount").value(12)).andExpect(jsonPath("$.hasNext").value(false));
        verify(service).list(USER, null, 20);
    }

    @Test
    void rendersWithdrawnActorAsNullAndKeepsMessage() throws Exception {
        when(service.list(USER, "cursor-value", 1)).thenReturn(new NotificationPageResult(List.of(
                new NotificationResult(NOTIFICATION, Type.LIKE, FEED, null,
                        "탈퇴한 회원이 좋아요를 눌렀습니다.", NOW, true)), "next", true, 4));
        mockMvc.perform(bearer(get(LIST).param("cursor", "cursor-value").param("size", "1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].actor").isEmpty())
                .andExpect(jsonPath("$.items[0].read").value(true))
                .andExpect(jsonPath("$.items[0].message").value("탈퇴한 회원이 좋아요를 눌렀습니다."))
                .andExpect(jsonPath("$.nextCursor").value("next")).andExpect(jsonPath("$.hasNext").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "text"})
    void rejectsInvalidPageSize(String size) throws Exception {
        mockMvc.perform(bearer(get(LIST).param("size", size))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void invalidCursorAndMissingIntegrationUseDocumentedErrors() throws Exception {
        when(service.list(USER, "invalid", 20)).thenThrow(new InvalidNotificationCursorException());
        mockMvc.perform(bearer(get(LIST).param("cursor", "invalid"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        when(service.list(USER, null, 20)).thenThrow(new NotificationIntegrationUnavailableException());
        mockMvc.perform(bearer(get(LIST))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void listIsNeverPublicAndInvalidBearerCannotBecomeAnonymous() throws Exception {
        mockMvc.perform(get(LIST)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid or expired"));
        mockMvc.perform(get(LIST).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void readsOnlyRequestedNotificationUsingBearerAndRealCookieHeader() throws Exception {
        mockMvc.perform(readRequest("{\"read\":true}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        verify(service).markRead(USER, NOTIFICATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"read\":false}", "{\"read\":null}", "{}"})
    void rejectsFalseNullAndMissingReadWithFieldErrors(String body) throws Exception {
        mockMvc.perform(readRequest(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("read"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"read\":\"true\"}", "{\"read\":1}", "{\"read\":[]}", "{\"read\":{}}"})
    void rejectsNonBooleanReadValuesRatherThanCoercing(String body) throws Exception {
        mockMvc.perform(readRequest(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void missingBodyAndInvalidUuidAreValidationErrors() throws Exception {
        mockMvc.perform(withCsrf(bearer(patch(UPDATE))).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(withCsrf(bearer(patch(LIST + "/not-a-uuid"))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"read\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void ownedDeletedNotificationIs404AndOtherRecipientsIs403() throws Exception {
        doThrow(new NotificationNotFoundException()).when(service).markRead(USER, NOTIFICATION);
        mockMvc.perform(readRequest("{\"read\":true}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        doThrow(new NotificationAccessDeniedException()).when(service).markRead(USER, NOTIFICATION);
        mockMvc.perform(readRequest("{\"read\":true}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void cancelledLikeReadReturns404WithoutAReadResponseBody() throws Exception {
        doThrow(new NotificationNotFoundException()).when(service).markRead(USER, NOTIFICATION);

        mockMvc.perform(readRequest("{\"read\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("urn:harudle:problem:notification-not-found"));
    }

    @Test
    void inactiveMemberOrGuestActorReturns401() throws Exception {
        when(service.list(USER, null, 20)).thenThrow(new AuthenticationRequiredException());
        mockMvc.perform(bearer(get(LIST))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        doThrow(new AuthenticationRequiredException()).when(service).markRead(USER, NOTIFICATION);
        mockMvc.perform(readRequest("{\"read\":true}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void validBearerStillRequiresMatchingCookieAndHeaderForPatch() throws Exception {
        mockMvc.perform(bearer(patch(UPDATE))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mockMvc.perform(bearer(patch(UPDATE)).header("X-XSRF-TOKEN", CSRF))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mockMvc.perform(bearer(patch(UPDATE)).cookie(new Cookie("XSRF-TOKEN", CSRF))
                        .header("X-XSRF-TOKEN", "different"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(service);
    }

    @Test
    void noBearerOrInvalidBearerReturns401BeforeCsrf() throws Exception {
        mockMvc.perform(patch(UPDATE)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(withCsrf(patch(UPDATE))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid"));
        mockMvc.perform(patch(UPDATE).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void unrelatedBearerMutationKeepsExistingSecurityBehaviour() throws Exception {
        mockMvc.perform(bearer(put("/api/v1/notification-security-probe")))
                .andExpect(status().isOk()).andExpect(content().string("unchanged"));
    }

    private MockHttpServletRequestBuilder readRequest(String body) {
        return withCsrf(bearer(patch(UPDATE))).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
    }

    @RestController
    static class UnrelatedController {
        @PutMapping("/api/v1/notification-security-probe")
        String probe() { return "unchanged"; }
    }
}
