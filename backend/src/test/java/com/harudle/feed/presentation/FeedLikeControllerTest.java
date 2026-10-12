package com.harudle.feed.presentation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.FeedLikeSecurityConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.feed.service.FeedLikeService;
import com.harudle.feed.service.dto.FeedLikeResult;
import com.harudle.feed.service.exception.FeedNotFoundException;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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

@WebMvcTest(FeedLikeController.class)
@Import({AuthenticatedUserIdResolver.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, CsrfConfiguration.class, SecurityConfig.class, FeedLikeSecurityConfiguration.class,
        FeedLikeControllerTest.UnrelatedController.class})
class FeedLikeControllerTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final UUID FEED_ID = UUID.fromString("6b66acba-0136-4822-8a59-f355dd7c977d");
    private static final String PATH = "/api/v1/feeds/" + FEED_ID + "/like";
    private static final String CSRF_TOKEN = "real-cookie-header-value";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private FeedLikeService service;
    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private OAuthLoginSuccessHandler successHandler;
    @MockitoBean
    private OAuthLoginFailureHandler failureHandler;
    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("valid-token")).thenReturn(Jwt.withTokenValue("valid-token")
                .header("alg", "HS256").subject(USER_ID.toString())
                .issuedAt(Instant.parse("2026-10-12T00:00:00Z"))
                .expiresAt(Instant.parse("2026-10-12T01:00:00Z")).build());
    }

    @Test
    void putsLikeUsingRealBearerAuthenticationAndCookieHeader() throws Exception {
        when(service.like(USER_ID, FEED_ID)).thenReturn(new FeedLikeResult(true, 13));

        mockMvc.perform(withCsrf(bearer(put(PATH))))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.likedByMe").value(true)).andExpect(jsonPath("$.likeCount").value(13));

        verify(service).like(USER_ID, FEED_ID);
    }

    @Test
    void deletesLikeUsingTheSameAuthenticationAndCsrfContract() throws Exception {
        when(service.unlike(USER_ID, FEED_ID)).thenReturn(new FeedLikeResult(false, 12));

        mockMvc.perform(withCsrf(bearer(delete(PATH))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.likedByMe").value(false))
                .andExpect(jsonPath("$.likeCount").value(12));

        verify(service).unlike(USER_ID, FEED_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void rejectsMissingCsrfWithValidBearer(String method) throws Exception {
        mockMvc.perform(bearer(mutation(method)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void requiresTheCookieAsWellAsTheCsrfHeader() throws Exception {
        mockMvc.perform(bearer(put(PATH)).header("X-XSRF-TOKEN", CSRF_TOKEN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsCookieHeaderMismatchAndMissingHeader() throws Exception {
        mockMvc.perform(bearer(put(PATH)).cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN))
                        .header("X-XSRF-TOKEN", "different-value"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        mockMvc.perform(bearer(delete(PATH)).cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void unauthenticatedRequestsReturn401BeforeCsrf(String method) throws Exception {
        mockMvc.perform(mutation(method)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
        mockMvc.perform(withCsrf(mutation(method))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void invalidBearerReturns401EvenWhenCsrfIsMissing() throws Exception {
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid"));
        mockMvc.perform(put(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void returnsFeedNotFoundAndInactiveMemberErrorsAsProblemDetails() throws Exception {
        when(service.like(USER_ID, FEED_ID)).thenThrow(new FeedNotFoundException());
        mockMvc.perform(withCsrf(bearer(put(PATH))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("urn:harudle:problem:feed-not-found"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        when(service.unlike(USER_ID, FEED_ID)).thenThrow(new AuthenticationRequiredException());
        mockMvc.perform(withCsrf(bearer(delete(PATH))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsMalformedFeedIdBeforeCallingService() throws Exception {
        mockMvc.perform(withCsrf(bearer(put("/api/v1/feeds/not-a-uuid/like"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }

    @Test
    void doesNotAddCsrfRequirementsToExistingBearerApis() throws Exception {
        mockMvc.perform(bearer(put("/api/v1/feed-security-probe")))
                .andExpect(status().isOk()).andExpect(content().string("unchanged"));
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder mutation(String method) {
        return method.equals("PUT") ? put(PATH) : delete(PATH);
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    @RestController
    static class UnrelatedController {

        @PutMapping("/api/v1/feed-security-probe")
        String putProbe() {
            return "unchanged";
        }
    }
}
