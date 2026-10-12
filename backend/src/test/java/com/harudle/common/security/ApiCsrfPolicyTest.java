package com.harudle.common.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(ApiCsrfPolicyTest.PolicyTestController.class)
@Import({
        ApiCsrfPolicyTest.PolicyTestController.class, SecurityConfig.class, CsrfConfiguration.class,
        TimeConfiguration.class, ApiExceptionLoggerTestConfiguration.class,
        ProblemDetailFactory.class, TraceIdConfiguration.class
})
class ApiCsrfPolicyTest {

    private static final UUID ACTOR = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final List<String> COOKIE_PATHS = List.of(
            "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/guest/session", "/api/v1/guest/diaries"
    );
    private static final List<HttpMethod> MUTATION_METHODS = List.of(
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE
    );

    @Autowired private MockMvc mockMvc;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private OAuthLoginSuccessHandler loginSuccessHandler;
    @MockitoBean private OAuthLoginFailureHandler loginFailureHandler;
    @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid")
                .header("alg", "RS256").subject(ACTOR.toString()).build());
    }

    @ParameterizedTest
    @MethodSource("bearerMutations")
    void acceptsMemberAndAdminMutationsWithoutCsrf(String path, HttpMethod method) throws Exception {
        User admin = mock(User.class);
        when(admin.isAdmin()).thenReturn(true);
        when(userRepository.findById(ACTOR)).thenReturn(Optional.of(admin));

        mockMvc.perform(request(method, path).header("Authorization", "Bearer valid"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("mutationMethods")
    void stillRejectsMissingBearer(HttpMethod method) throws Exception {
        mockMvc.perform(request(method, "/api/v1/csrf-policy-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @MethodSource("mutationMethods")
    void stillRejectsInvalidBearer(HttpMethod method) throws Exception {
        when(jwtDecoder.decode("expired")).thenThrow(new BadJwtException("expired"));
        mockMvc.perform(request(method, "/api/v1/csrf-policy-test")
                        .header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("invalidCookieRequests")
    void rejectsCookieMutationsWithoutMatchingCsrfEvenWithBearer(
            String path, boolean bearer, String suppliedToken
    ) throws Exception {
        var request = post(path);
        if (bearer) {
            request.header("Authorization", "Bearer valid");
        }
        if ("COOKIE_ONLY".equals(suppliedToken) || "MISMATCHED".equals(suppliedToken)) {
            request.cookie(new Cookie("XSRF-TOKEN", "test-csrf"));
        }
        if ("HEADER_ONLY".equals(suppliedToken) || "MISMATCHED".equals(suppliedToken)) {
            request.header("X-XSRF-TOKEN", "MISMATCHED".equals(suppliedToken) ? "wrong" : "test-csrf");
        }
        mockMvc.perform(request).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        verifyNoInteractions(jwtDecoder);
    }

    @ParameterizedTest
    @MethodSource("cookieRequests")
    void acceptsCookieMutationsWithMatchingCsrf(String path, boolean bearer) throws Exception {
        var request = post(path).cookie(new Cookie("XSRF-TOKEN", "test-csrf"))
                .header("X-XSRF-TOKEN", "test-csrf");
        if (bearer) {
            request.header("Authorization", "Bearer valid");
        }
        mockMvc.perform(request).andExpect(status().isOk());
    }

    private static Stream<Arguments> bearerMutations() {
        return Stream.of("/api/v1/csrf-policy-test", "/api/v1/admin/csrf-policy-test")
                .flatMap(path -> MUTATION_METHODS.stream().map(method -> Arguments.of(path, method)));
    }

    private static Stream<HttpMethod> mutationMethods() {
        return MUTATION_METHODS.stream();
    }

    private static Stream<Arguments> cookieRequests() {
        return COOKIE_PATHS.stream()
                .flatMap(path -> Stream.of(false, true).map(bearer -> Arguments.of(path, bearer)));
    }

    private static Stream<Arguments> invalidCookieRequests() {
        return COOKIE_PATHS.stream().flatMap(path -> Stream.of(false, true)
                .flatMap(bearer -> Stream.of("NONE", "COOKIE_ONLY", "HEADER_ONLY", "MISMATCHED")
                        .map(suppliedToken -> Arguments.of(path, bearer, suppliedToken))));
    }

    @RestController
    @TestComponent
    static class PolicyTestController {

        @RequestMapping(
                path = {"/api/v1/csrf-policy-test", "/api/v1/admin/csrf-policy-test"},
                method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE}
        )
        String bearerMutation() {
            return "bearer";
        }

        @PostMapping({"/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/guest/session", "/api/v1/guest/diaries"})
        String cookieMutation() {
            return "cookie";
        }
    }
}
