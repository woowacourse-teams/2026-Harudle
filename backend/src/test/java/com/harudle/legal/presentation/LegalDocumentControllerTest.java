package com.harudle.legal.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.OAuthLoginFailureHandler;
import com.harudle.auth.infrastructure.oauth.OAuthLoginSuccessHandler;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.legal.application.LegalDocumentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LegalDocumentController.class)
@Import({LegalDocumentCatalog.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, CsrfConfiguration.class, SecurityConfig.class})
class LegalDocumentControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private OAuthLoginSuccessHandler successHandler;
    @MockitoBean private OAuthLoginFailureHandler failureHandler;
    @MockitoBean private ClientRegistrationRepository registrations;

    @Test
    @DisplayName("비로그인 사용자도 두 문서 전문·시행일·버전·고정 해시를 열람한다")
    void allowsAnonymousDocumentReading() throws Exception {
        mockMvc.perform(get("/api/v1/legal/documents"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath("$.documents[0].type").value("TERMS"))
                .andExpect(jsonPath("$.documents[0].version").value("2026-10-22"))
                .andExpect(jsonPath("$.documents[0].effectiveDate").value("2026-10-22"))
                .andExpect(jsonPath("$.documents[0].contentHash")
                        .value("ec0c325c8fe651c76e5134b9eeea055613c08de0350398cf09f899f64e479487"))
                .andExpect(jsonPath("$.documents[0].sections[0].paragraphs[0]").isString())
                .andExpect(jsonPath("$.documents[1].type").value("PRIVACY"))
                .andExpect(jsonPath("$.documents[1].version").value("2026-08-24"));
    }

    @Test
    @DisplayName("공개된 종류·버전의 단일 문서를 비로그인 상태로 열람한다")
    void readsPublishedVersion() throws Exception {
        mockMvc.perform(get("/api/v1/legal/documents/terms/2026-10-22"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.title").value("하루들 서비스 이용약관"))
                .andExpect(jsonPath("$.sourceUrl")
                        .value("https://sun-acai-a8d.notion.site/409676b0adce8380b97101bd50ec7dd9"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"terms/2026-10-23", "TERMS/2026-10-22", "unknown/2026-10-22"})
    @DisplayName("공개하지 않은 종류·버전은 인증 요구나 내부 오류 대신 문서 404를 반환한다")
    void rejectsUnknownDocumentAs404(String path) throws Exception {
        mockMvc.perform(get("/api/v1/legal/documents/" + path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEGAL_DOCUMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isString());
    }
}
