package com.harudle.admin.presentation;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.harudle.admin.service.AdminR2ImageRecoveryService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.common.security.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class AdminR2ImageRecoveryControllerTest {
    private static final String ENDPOINT = "/api/v1/admin/generations/restore-image/r2";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID GENERATION_ID = UUID.randomUUID();
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AdminR2ImageRecoveryService service;
    private UserRepository users;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        service = context.getBean(AdminR2ImageRecoveryService.class);
        users = context.getBean(UserRepository.class);
        User admin = mock(User.class);
        when(admin.isAdmin()).thenReturn(true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(admin));
        when(service.recover("dev", List.of(GENERATION_ID), true)).thenReturn(
                new AdminR2ImageRecoveryService.BatchResult(UUID.randomUUID(), "dev", true, List.of()));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void adminWithCsrfCanPreview() throws Exception {
        mvc.perform(post(ENDPOINT).with(jwt().jwt(builder -> builder.subject(USER_ID.toString())))
                        .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body("true")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true));
        verify(service).recover("dev", List.of(GENERATION_ID), true);
    }

    @Test
    void anonymousCannotInvokeRecovery() throws Exception {
        mvc.perform(post(ENDPOINT).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body("true")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void ordinaryUserCannotInvokeRecovery() throws Exception {
        when(users.findById(USER_ID)).thenReturn(Optional.of(mock(User.class)));
        mvc.perform(post(ENDPOINT).with(jwt().jwt(builder -> builder.subject(USER_ID.toString())))
                        .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body("false")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminWithBearerCanWriteWithoutCsrf() throws Exception {
        when(context.getBean(JwtDecoder.class).decode("valid")).thenReturn(Jwt.withTokenValue("valid")
                .header("alg", "RS256").subject(USER_ID.toString()).build());
        when(service.recover("dev", List.of(GENERATION_ID), false)).thenReturn(
                new AdminR2ImageRecoveryService.BatchResult(UUID.randomUUID(), "dev", false, List.of()));
        mvc.perform(post(ENDPOINT).header("Authorization", "Bearer valid")
                        .contentType(MediaType.APPLICATION_JSON).content(body("false")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(false));
        verify(service).recover("dev", List.of(GENERATION_ID), false);
    }

    @Test
    void requiresExplicitDryRunMode() throws Exception {
        mvc.perform(post(ENDPOINT).with(jwt().jwt(builder -> builder.subject(USER_ID.toString())))
                        .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON).content(body("null")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void emptyScopeIsRejected() throws Exception {
        mvc.perform(post(ENDPOINT).with(jwt().jwt(builder -> builder.subject(USER_ID.toString())))
                        .with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environment\":\"dev\",\"generationIds\":[],\"dryRun\":true}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String body(String dryRun) {
        return "{\"environment\":\"dev\",\"generationIds\":[\"" + GENERATION_ID + "\"],\"dryRun\":" + dryRun + "}";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {
        @Bean AdminR2ImageRecoveryService service() { return mock(AdminR2ImageRecoveryService.class); }
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean JwtDecoder decoder() { return mock(JwtDecoder.class); }
        @Bean AdminR2ImageRecoveryController controller(AdminR2ImageRecoveryService service) {
            return new AdminR2ImageRecoveryController(service);
        }
        @Bean SecurityFilterChain security(HttpSecurity http, UserRepository users) throws Exception {
            var entryPoint = mock(ApiAuthenticationEntryPoint.class);
            doAnswer(invocation -> {
                ((jakarta.servlet.http.HttpServletResponse) invocation.getArgument(1)).setStatus(401);
                return null;
            }).when(entryPoint).commence(any(), any(), any());
            var deniedHandler = mock(ApiAccessDeniedHandler.class);
            doAnswer(invocation -> {
                ((jakarta.servlet.http.HttpServletResponse) invocation.getArgument(1)).setStatus(403);
                return null;
            }).when(deniedHandler).handle(any(), any(), any());
            // 운영 SecurityConfig의 경로 매칭, 관리자 권한 검사와 CSRF 필터를 그대로 사용한다.
            return new SecurityConfig(mock(OAuthLoginSuccessHandler.class), mock(OAuthLoginFailureHandler.class))
                    .apiSecurityFilterChain(http, CookieCsrfTokenRepository.withHttpOnlyFalse(), entryPoint,
                            deniedHandler, new AdminAuthorizationManager(users));
        }
    }
}
