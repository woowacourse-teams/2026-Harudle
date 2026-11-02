package com.harudle.moderation.presentation;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.moderation.domain.ModerationChange;
import com.harudle.moderation.domain.ModerationState;
import com.harudle.moderation.domain.ModerationTargetType;
import com.harudle.moderation.service.AdminModerationService;
import com.harudle.moderation.service.ModerationConflictException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminModerationController.class)
@Import({AuthenticatedUserIdResolver.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, CsrfConfiguration.class, SecurityConfig.class})
class AdminModerationControllerTest {
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final String PATH = "/api/v1/admin/moderation/USER/" + TARGET;
    @Autowired private MockMvc mvc;
    @MockitoBean private AdminModerationService service;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private OAuthLoginSuccessHandler success;
    @MockitoBean private OAuthLoginFailureHandler failure;
    @MockitoBean private ClientRegistrationRepository registrations;

    @BeforeEach
    void admin() {
        var jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject(ADMIN.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(1800)).build();
        when(decoder.decode("token")).thenReturn(jwt);
        var user = mock(User.class);
        when(user.isAdmin()).thenReturn(true);
        when(users.findById(ADMIN)).thenReturn(Optional.of(user));
    }

    @Test
    void usesAuthenticatedActorAndReturnsNoStoreState() throws Exception {
        when(service.change(eq(ADMIN), eq(ModerationTargetType.USER), eq(TARGET), any()))
                .thenReturn(new ModerationState(ModerationTargetType.USER, TARGET, true, 1, "사유", ADMIN, Instant.now()));
        mvc.perform(put(PATH).header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restricted":true,"expectedVersion":0,"reason":" 사유 ","actorId":"forged"}
                        """))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.version").value(1));
        verify(service).change(ADMIN, ModerationTargetType.USER, TARGET, new ModerationChange(true, 0, "사유"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"restricted\":true}", "{\"restricted\":true,\"expectedVersion\":0,\"reason\":\" \"}",
            "{\"restricted\":true,\"expectedVersion\":-1,\"reason\":\"사유\"}"})
    void validatesRequiredActionVersionAndReason(String body) throws Exception {
        mvc.perform(put(PATH).header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void staleVersionIsConflict() throws Exception {
        when(service.change(any(), any(), any(), any())).thenThrow(new ModerationConflictException());
        mvc.perform(put(PATH).header("Authorization", "Bearer token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"restricted\":false,\"expectedVersion\":1,\"reason\":\"해제\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODERATION_CONFLICT"));
    }

    @Test
    void blocksAnonymousAndOrdinaryUser() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        var ordinary = mock(User.class);
        when(users.findById(ADMIN)).thenReturn(Optional.of(ordinary));
        mvc.perform(get(PATH).header("Authorization", "Bearer token")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
